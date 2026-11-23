package cl.etimo.initiumdb.integration.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.EngineScenario;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.oracle.OracleContainer;

/**
 * Contract: README.md > "SQL identifiers and quoting", against a disposable Oracle. Object names
 * are written as in the data dictionary; SQL follows Oracle's own case and quoting.
 */
@Tag("oracle_integration")
class OracleRoundTripTest {

  private static final OracleContainer ORACLE =
      new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

  /**
   * The schema and {@code Customer} are quoted with mixed case; {@code ACCOUNT} is unquoted (upper
   * case).
   */
  private static final String QUERIES =
      "schema: CrmOwner\n"
          + "tables:\n"
          + "  - name: Customer\n"
          + "    sql: SELECT \"Id\", \"FullName\" FROM \"Customer\" ORDER BY \"Id\"\n"
          + "  - name: ACCOUNT\n"
          + "    sql: select * from account order by account_id\n";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    ORACLE.start();
    execute(
        "CREATE USER \"CrmOwner\" IDENTIFIED BY \"Owner#12345\" QUOTA UNLIMITED ON USERS",
        "CREATE TABLE \"CrmOwner\".\"Customer\" (\"Id\" NUMBER PRIMARY KEY,"
            + " \"FullName\" VARCHAR2(50))",
        "CREATE TABLE \"CrmOwner\".account (account_id NUMBER PRIMARY KEY, balance NUMBER)");
  }

  @AfterAll
  static void stopDatabase() {
    ORACLE.stop();
  }

  /** The disposable instance's {@code system} account owns the schema and runs InitiumDb. */
  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword());
  }

  private static void execute(String... statements) throws SQLException {
    try (Connection c = connection();
        Statement s = c.createStatement()) {
      for (String statement : statements) {
        s.execute(statement);
      }
    }
  }

  private static String scalar(String query) throws SQLException {
    try (Connection c = connection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(query)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private EngineScenario scenario(String queries) throws Exception {
    return new EngineScenario(
        root, "oracle", ORACLE.getJdbcUrl(), "system", ORACLE.getPassword(), queries);
  }

  @Test
  void capturedRowsRestoreAfterTheyAreDeleted() throws Exception {
    // Spec: README.md > "SQL identifiers and quoting"
    // Given
    execute(
        "DELETE FROM \"CrmOwner\".account",
        "DELETE FROM \"CrmOwner\".\"Customer\"",
        "INSERT INTO \"CrmOwner\".\"Customer\" VALUES (1, 'Ana')",
        "INSERT INTO \"CrmOwner\".\"Customer\" VALUES (2, 'Beto')",
        "INSERT INTO \"CrmOwner\".account VALUES (10, 500)");
    EngineScenario scenario = scenario(QUERIES);

    // When
    EngineScenario.Result captured = scenario.capture();
    execute("DELETE FROM \"CrmOwner\".account", "DELETE FROM \"CrmOwner\".\"Customer\"");
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    assertThat(scenario.readDataset()).contains("<Customer").contains("FullName=\"Ana\"");
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT \"FullName\" FROM \"CrmOwner\".\"Customer\" WHERE \"Id\" = 2"))
        .isEqualTo("Beto");
    assertThat(scalar("SELECT balance FROM \"CrmOwner\".account WHERE account_id = 10"))
        .isEqualTo("500");
  }

  @Test
  void aSchemaWrittenWithTheWrongCaseIsRejected() throws Exception {
    // Spec: README.md > "SQL identifiers and quoting" (object names are never case-folded)
    // Given
    EngineScenario scenario = scenario(QUERIES.replace("schema: CrmOwner", "schema: CRMOWNER"));

    // When
    EngineScenario.Result captured = scenario.capture();

    // Then
    assertThat(captured.exitCode()).isEqualTo(1);
    assertThat(scenario.dataset()).doesNotExist();
  }
}
