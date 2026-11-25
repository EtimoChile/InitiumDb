package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.core.CaseConfig.DatabaseStep;
import cl.etimo.initiumdb.core.SqlScript.Guard;
import cl.etimo.initiumdb.core.SqlScript.Statement;
import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.Engines;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.DataSetException;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITableMetaData;
import org.dbunit.dataset.stream.IDataSetConsumer;
import org.dbunit.dataset.stream.IDataSetProducer;
import org.dbunit.dataset.stream.StreamingDataSet;
import org.dbunit.dataset.xml.FlatXmlProducer;
import org.dbunit.operation.DatabaseOperation;
import org.xml.sax.InputSource;

/**
 * Implements {@code idb-prepare}: per step, the optional {@code <ALIAS>.pre.sql} hook, DBUnit
 * {@code REFRESH} of the dataset in its declared table order, then the optional {@code
 * <ALIAS>.post.sql} hook.
 *
 * <p>SQL hooks run in autocommit, exactly as written. The dataset is applied in one transaction
 * (all or nothing). Nothing is rolled back across hooks, the dataset or steps; a failed run is
 * recovered by running the whole command again (DEC-006).
 */
public final class PrepareService {

  private PrepareService() {}

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
        prepareStep(step, environment, resolver, out);
        out.println(label + ": prepared from " + step.dataset());
      } catch (InitiumDbException e) {
        throw new InitiumDbException(label + ": " + e.getMessage());
      } catch (SQLException | DatabaseUnitException | IOException e) {
        throw new InitiumDbException(label + ": " + CaptureService.describe(e));
      }
    }
  }

  private static void prepareStep(
      DatabaseStep step,
      EnvironmentConfig environment,
      ConnectionResolver resolver,
      PrintStream out)
      throws SQLException, DatabaseUnitException, IOException {
    DatabaseEngine engine = Engines.named(environment.database(step.alias()).engine());
    requireFile(step.dataset(), "Dataset");
    List<Statement> pre = script(step.preSql());
    List<Statement> post = script(step.postSql());
    String schema =
        Files.isRegularFile(step.queries()) ? QueriesConfig.load(step.queries()).schema() : null;
    IDataSet dataSet = streamingDataSet(step.dataset(), out);

    try (Connection connection = resolver.resolve(environment.environment(), step.alias())) {
      connection.setAutoCommit(true);
      engine.useSchema(connection, schema);
      execute(connection, pre, "pre hook", out);
      refresh(connection, engine, schema, dataSet);
      execute(connection, post, "post hook", out);
    }
  }

  /**
   * DBUnit's {@code REFRESH} issues its statements row by row; under autocommit that would commit
   * once per row. The dataset is applied in one short transaction instead, so it is both efficient
   * and all-or-nothing. It contains no user SQL, so no hook can interfere with the boundary.
   */
  private static void refresh(
      Connection connection, DatabaseEngine engine, String schema, IDataSet dataSet)
      throws SQLException, DatabaseUnitException {
    IDatabaseConnection dbunit = DbUnitSession.open(connection, engine, schema);
    connection.setAutoCommit(false);
    try {
      DatabaseOperation.REFRESH.execute(dbunit, dataSet);
      connection.commit();
    } catch (SQLException | DatabaseUnitException | RuntimeException e) {
      rollbackQuietly(connection);
      throw e;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  /**
   * The dataset is read as a stream (never fully in memory). Column sensing keeps the columns that
   * appear after the first row (null values are not written to Flat XML). Progress is reported per
   * table as its rows go by.
   */
  private static IDataSet streamingDataSet(Path file, PrintStream out) {
    IDataSetProducer producer =
        new FlatXmlProducer(new InputSource(file.toUri().toString()), false, true, true);
    return new StreamingDataSet(new ProgressProducer(producer, out));
  }

  /** Reports the row count of each table once its last row has been read. */
  private static final class ProgressProducer implements IDataSetProducer, IDataSetConsumer {

    private final IDataSetProducer delegate;
    private final PrintStream out;
    private IDataSetConsumer consumer;
    private String table;
    private long rows;

    ProgressProducer(IDataSetProducer delegate, PrintStream out) {
      this.delegate = delegate;
      this.out = out;
    }

    @Override
    public void setConsumer(IDataSetConsumer consumer) throws DataSetException {
      this.consumer = consumer;
      delegate.setConsumer(this);
    }

    @Override
    public void produce() throws DataSetException {
      delegate.produce();
    }

    @Override
    public void startDataSet() throws DataSetException {
      consumer.startDataSet();
    }

    @Override
    public void endDataSet() throws DataSetException {
      report();
      consumer.endDataSet();
    }

    @Override
    public void startTable(ITableMetaData metaData) throws DataSetException {
      if (!metaData.getTableName().equals(table)) {
        report();
        table = metaData.getTableName();
      }
      consumer.startTable(metaData);
    }

    @Override
    public void endTable() throws DataSetException {
      consumer.endTable();
    }

    @Override
    public void row(Object[] values) throws DataSetException {
      rows++;
      consumer.row(values);
    }

    private void report() {
      if (table != null) {
        out.println("  " + table + ": " + rows + " row(s)");
      }
      table = null;
      rows = 0;
    }
  }

  private static void requireFile(Path file, String what) {
    if (!Files.isRegularFile(file)) {
      throw new InitiumDbException(what + " not found: " + file);
    }
  }

  /** A hook that does not exist is simply not run. */
  private static List<Statement> script(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    return SqlScript.statements(Files.readString(file, StandardCharsets.UTF_8));
  }

  private static void execute(
      Connection connection, List<Statement> statements, String label, PrintStream out)
      throws SQLException {
    if (statements.isEmpty()) {
      return;
    }
    int run = 0;
    int skipped = 0;
    for (Statement hook : statements) {
      if (hook.guard() != null && !guardAllows(connection, hook.guard(), label)) {
        skipped++;
        continue;
      }
      try (java.sql.Statement statement = connection.createStatement()) {
        statement.execute(hook.sql());
      } catch (SQLException e) {
        throw CaptureService.failed(label + " statement " + (run + skipped + 1), hook.sql(), e);
      }
      run++;
    }
    out.println("  " + label + ": " + run + " of " + statements.size() + " statement(s) run");
  }

  /** Runs a guard query: {@code run-if} needs a row, {@code skip-if} needs none. */
  private static boolean guardAllows(Connection connection, Guard guard, String label)
      throws SQLException {
    try (java.sql.Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(guard.query())) {
      return rows.next() == guard.runIf();
    } catch (SQLException e) {
      throw CaptureService.failed(label + " guard", guard.query(), e);
    }
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // The original failure is the one worth reporting.
    }
  }
}
