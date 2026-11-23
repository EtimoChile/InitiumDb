package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.core.CaseConfig.DatabaseStep;
import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.Engines;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.operation.DatabaseOperation;

/**
 * Implements {@code idb-prepare}: per step, optional {@code preSql}, DBUnit {@code REFRESH} of the
 * dataset in its declared table order, then optional {@code postSql}.
 *
 * <p>Each step is one transaction: committed when the whole step succeeds and rolled back when any
 * part fails. Earlier steps stay committed (there is no distributed transaction).
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
        prepareStep(step, environment, resolver);
        out.println(label + ": prepared from " + step.dataset());
      } catch (InitiumDbException e) {
        throw new InitiumDbException(label + ": " + e.getMessage());
      } catch (SQLException | DatabaseUnitException | IOException e) {
        throw new InitiumDbException(label + ": " + CaptureService.describe(e));
      }
    }
  }

  private static void prepareStep(
      DatabaseStep step, EnvironmentConfig environment, ConnectionResolver resolver)
      throws SQLException, DatabaseUnitException, IOException {
    DatabaseEngine engine = Engines.named(environment.database(step.alias()).engine());
    requireFile(step.dataset(), "Dataset");
    List<String> pre = script(step.preSql(), "preSql");
    List<String> post = script(step.postSql(), "postSql");
    String schema = step.queries() == null ? null : QueriesConfig.load(step.queries()).schema();
    IDataSet dataSet =
        new FlatXmlDataSetBuilder().setColumnSensing(true).build(step.dataset().toFile());

    try (Connection connection = resolver.resolve(environment.environment(), step.alias())) {
      connection.setAutoCommit(false);
      try {
        engine.useSchema(connection, schema);
        execute(connection, pre);
        IDatabaseConnection dbunit = DbUnitSession.open(connection, engine, schema);
        DatabaseOperation.REFRESH.execute(dbunit, dataSet);
        execute(connection, post);
        connection.commit();
      } catch (SQLException | DatabaseUnitException | RuntimeException e) {
        rollbackQuietly(connection);
        throw e;
      }
    }
  }

  private static void requireFile(Path file, String what) {
    if (!Files.isRegularFile(file)) {
      throw new InitiumDbException(what + " not found: " + file);
    }
  }

  private static List<String> script(Path file, String field) throws IOException {
    if (file == null) {
      return List.of();
    }
    requireFile(file, "SQL script '" + field + "'");
    return SqlScript.statements(Files.readString(file, StandardCharsets.UTF_8));
  }

  private static void execute(Connection connection, List<String> statements) throws SQLException {
    for (String sql : statements) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(sql);
      }
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
