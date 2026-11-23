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
import java.sql.SQLException;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.database.QueryDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSet;

/**
 * Implements {@code idb-capture}: runs the configured {@code SELECT}s in declared order and writes
 * a DBUnit Flat XML dataset per step. Data is not masked or anonymized.
 */
public final class CaptureService {

  private CaptureService() {}

  /** Runs every step in order and stops at the first failure. */
  public static void run(
      CaseConfig caseConfig,
      EnvironmentConfig environment,
      ConnectionResolver resolver,
      PrintStream out) {
    int index = 0;
    for (DatabaseStep step : caseConfig.databases()) {
      index++;
      String label = "Step " + index + " (" + step.alias() + ")";
      try {
        int tables = captureStep(step, environment, resolver);
        out.println(label + ": captured " + tables + " table(s) into " + step.dataset());
      } catch (InitiumDbException e) {
        throw new InitiumDbException(label + ": " + e.getMessage());
      } catch (SQLException | DatabaseUnitException | IOException e) {
        throw new InitiumDbException(label + ": " + describe(e));
      }
    }
  }

  private static int captureStep(
      DatabaseStep step, EnvironmentConfig environment, ConnectionResolver resolver)
      throws SQLException, DatabaseUnitException, IOException {
    if (step.queries() == null) {
      throw new InitiumDbException("The step declares no 'queries'; capture needs them.");
    }
    QueriesConfig queries = QueriesConfig.load(step.queries());
    DatabaseSettings settings = environment.database(step.alias());
    DatabaseEngine engine = Engines.named(settings.engine());
    try (Connection connection = resolver.resolve(environment.environment(), step.alias())) {
      connection.setAutoCommit(false);
      connection.setReadOnly(true);
      engine.useSchema(connection, queries.schema());
      IDatabaseConnection dbunit = DbUnitSession.open(connection, engine, queries.schema());
      QueryDataSet dataSet = new QueryDataSet(dbunit);
      for (TableQuery table : queries.tables()) {
        dataSet.addTable(table.name(), table.sql());
      }
      requireXmlNames(dataSet);
      write(dataSet, step.dataset());
      connection.rollback();
    }
    return queries.tables().size();
  }

  /**
   * Flat XML uses table names as element names and column names as attribute names, so both must be
   * valid XML names. Failing here avoids writing a dataset that {@code idb-prepare} cannot read.
   */
  private static void requireXmlNames(QueryDataSet dataSet) throws DatabaseUnitException {
    for (String table : dataSet.getTableNames()) {
      if (!isXmlName(table)) {
        throw new InitiumDbException(
            "Table name '"
                + table
                + "' is not a valid XML name (Flat XML datasets cannot hold spaces or most"
                + " symbols); rename the object.");
      }
      for (var column : dataSet.getTableMetaData(table).getColumns()) {
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

  /** Writes to a temporary file first so a failed capture never clobbers a good dataset. */
  private static void write(QueryDataSet dataSet, Path target)
      throws DatabaseUnitException, IOException {
    Path absolute = target.toAbsolutePath();
    Files.createDirectories(absolute.getParent());
    Path temp = Files.createTempFile(absolute.getParent(), ".idb-capture-", ".tmp");
    try {
      try (OutputStream stream = Files.newOutputStream(temp)) {
        FlatXmlDataSet.write(dataSet, stream);
      }
      Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  static String describe(Exception e) {
    if (e instanceof SQLException sql) {
      return "database error (SQLState " + sql.getSQLState() + "): " + sql.getMessage();
    }
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
