package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.core.CaseConfig.DatabaseStep;
import cl.etimo.initiumdb.core.EnvironmentConfig.DatabaseSettings;
import cl.etimo.initiumdb.core.QueriesConfig.TableQuery;
import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.Engines;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.database.ResultSetTableMetaData;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.DataSetException;
import org.dbunit.dataset.ITableMetaData;
import org.dbunit.dataset.xml.FlatXmlWriter;

/**
 * Implements {@code idb-capture}: runs the configured {@code SELECT}s in declared order and writes
 * a DBUnit Flat XML dataset per alias. Data is not masked or anonymized.
 */
public final class CaptureService {

  private static final int FETCH_SIZE = 1000;

  /** Comments before the code of a statement say nothing about why it failed. */
  private static final java.util.regex.Pattern LEADING_COMMENTS =
      java.util.regex.Pattern.compile(
          "\\A(\\s*(--[^\\r\\n]*|/\\*.*?\\*/))*\\s*", java.util.regex.Pattern.DOTALL);

  private CaptureService() {}

  /** Runs every alias in order and stops at the first failure. */
  public static void run(
      CaseConfig caseConfig,
      EnvironmentConfig environment,
      ConnectionResolver resolver,
      PrintStream out) {
    int index = 0;
    for (DatabaseStep step : caseConfig.databases()) {
      index++;
      String label =
          "Alias "
              + step.alias()
              + " ("
              + environment.environment()
              + ") "
              + index
              + " of "
              + caseConfig.databases().size();
      long start = System.nanoTime();
      try {
        int tables = captureStep(step, environment, resolver, out);
        out.println(
            label
                + ": captured "
                + tables
                + " table(s) into "
                + step.dataset()
                + " in "
                + PrepareService.seconds(System.nanoTime() - start));
      } catch (InitiumDbException e) {
        throw new InitiumDbException(label + ": " + e.getMessage());
      } catch (SQLException | DatabaseUnitException | IOException e) {
        throw new InitiumDbException(label + ": " + describe(e));
      }
    }
  }

  private static int captureStep(
      DatabaseStep step,
      EnvironmentConfig environment,
      ConnectionResolver resolver,
      PrintStream out)
      throws SQLException, DatabaseUnitException, IOException {
    if (!Files.isRegularFile(step.queries())) {
      throw new InitiumDbException("Queries file not found: " + step.queries());
    }
    QueriesConfig queries = QueriesConfig.load(step.queries());
    DatabaseSettings settings = environment.database(step.alias());
    DatabaseEngine engine = Engines.named(settings.engine());
    try (Connection connection = resolver.resolve(environment.environment(), step.alias())) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      engine.useSchema(connection, queries.schema());
      IDatabaseConnection dbunit = DbUnitSession.open(connection, engine, queries.schema());
      write(connection, dbunit, queries, step.dataset(), out);
      connection.rollback();
    }
    return queries.tables().size();
  }

  /**
   * Flat XML uses table names as element names and column names as attribute names, so both must be
   * valid XML names. Failing before the table is written avoids a dataset that {@code idb-prepare}
   * cannot read.
   */
  private static void requireXmlNames(ITableMetaData meta) throws DataSetException {
    String table = meta.getTableName();
    if (!isXmlName(table)) {
      throw new InitiumDbException(
          "Table name '"
              + table
              + "' is not a valid XML name (Flat XML datasets cannot hold spaces or most"
              + " symbols); rename the object.");
    }
    for (Column column : meta.getColumns()) {
      if (!isXmlName(column.getColumnName())) {
        throw new InitiumDbException(
            "Column '"
                + column.getColumnName()
                + "' of table '"
                + table
                + "' is not a valid XML name (Flat XML datasets cannot hold spaces or most"
                + " symbols); rename the object.");
      }
    }
  }

  static boolean isXmlName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean start = Character.isLetter(c) || c == '_' || c == ':';
      boolean part = start || Character.isDigit(c) || c == '-' || c == '.';
      if (i == 0 ? !start : !part) {
        return false;
      }
    }
    return true;
  }

  /**
   * Streams every query straight into a temporary file (rows are never held in memory, and nothing
   * extra is run to count them), then moves it over the target, so a failed capture never clobbers
   * a good dataset.
   */
  private static void write(
      Connection connection,
      IDatabaseConnection dbunit,
      QueriesConfig queries,
      Path target,
      PrintStream out)
      throws SQLException, DatabaseUnitException, IOException {
    Path absolute = target.toAbsolutePath();
    Files.createDirectories(absolute.getParent());
    Path temp = Files.createTempFile(absolute.getParent(), ".idb-capture-", ".tmp");
    Path withDtd = Files.createTempFile(absolute.getParent(), ".idb-capture-", ".tmp");
    try {
      Map<String, Set<String>> columns = new LinkedHashMap<>();
      try (OutputStream stream = Files.newOutputStream(temp)) {
        FlatXmlWriter writer = new FlatXmlWriter(stream);
        writer.startDataSet();
        for (TableQuery table : queries.tables()) {
          long start = System.nanoTime();
          long rows = writeTable(connection, dbunit, table, writer, columns);
          out.println(
              "  "
                  + table.name()
                  + ": "
                  + rows
                  + " row(s) in "
                  + PrepareService.seconds(System.nanoTime() - start));
        }
        writer.endDataSet();
      }
      // Flat XML omits NULLs, so the columns are also declared in an internal DTD
      DatasetColumns.writeWithDtd(temp, withDtd, columns);
      Files.move(withDtd, absolute, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
      Files.deleteIfExists(withDtd);
    }
  }

  private static long writeTable(
      Connection connection,
      IDatabaseConnection dbunit,
      TableQuery table,
      FlatXmlWriter writer,
      Map<String, Set<String>> columns)
      throws SQLException, DatabaseUnitException {
    try {
      return streamTable(connection, dbunit, table, writer, columns);
    } catch (SQLException e) {
      throw failed("Table '" + table.name() + "' query", table.sql(), e);
    }
  }

  private static long streamTable(
      Connection connection,
      IDatabaseConnection dbunit,
      TableQuery table,
      FlatXmlWriter writer,
      Map<String, Set<String>> declared)
      throws SQLException, DatabaseUnitException {
    try (Statement statement = connection.createStatement()) {
      statement.setFetchSize(FETCH_SIZE);
      try (ResultSet rs = statement.executeQuery(table.sql())) {
        ITableMetaData meta = new ResultSetTableMetaData(table.name(), rs, dbunit, true);
        requireXmlNames(meta);
        Column[] columns = meta.getColumns();
        Set<String> names = declared.computeIfAbsent(table.name(), k -> new LinkedHashSet<>());
        for (Column column : columns) {
          names.add(column.getColumnName());
        }
        writer.startTable(meta);
        long rows = 0;
        while (rs.next()) {
          Object[] values = new Object[columns.length];
          for (int i = 0; i < columns.length; i++) {
            values[i] = columns[i].getDataType().getSqlValue(i + 1, rs);
          }
          writer.row(values);
          rows++;
        }
        writer.endTable();
        return rows;
      }
    }
  }

  /** A SQL failure that names what was running and the statement itself (trusted project SQL). */
  static InitiumDbException failed(String what, String sql, SQLException e) {
    String code = LEADING_COMMENTS.matcher(sql).replaceFirst("");
    String oneLine = (code.isBlank() ? sql : code).strip().replaceAll("\\s+", " ");
    if (oneLine.length() > 500) {
      oneLine = oneLine.substring(0, 500) + "...";
    }
    return new InitiumDbException(what + " failed: " + describe(e) + " [SQL: " + oneLine + "]");
  }

  static String describe(Exception e) {
    if (e instanceof SQLException sql) {
      return "database error (SQLState " + sql.getSQLState() + "): " + sql.getMessage();
    }
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
