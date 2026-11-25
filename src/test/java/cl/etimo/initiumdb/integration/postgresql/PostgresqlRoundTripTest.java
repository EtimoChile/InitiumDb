package cl.etimo.initiumdb.integration.postgresql;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contract: README.md > "SQL identifiers and quoting", against a disposable PostgreSQL. Object
 * names are written as in the data dictionary; SQL follows PostgreSQL's own case and quoting.
 */
@Tag("postgresql_integration")
class PostgresqlRoundTripTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  /** Schema, table and columns of {@code Customer} are quoted: mixed case. */
  private static final String QUERIES =
      "schema: Crm Owner\n"
          + "tables:\n"
          + "  - name: Customer\n"
          + "    sql: SELECT \"Id\", \"FullName\" FROM \"Customer\" ORDER BY \"Id\"\n"
          + "  - name: account\n"
          + "    sql: SELECT * FROM account ORDER BY account_id\n";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    POSTGRES.start();
    execute(
        "CREATE SCHEMA \"Crm Owner\"",
        "CREATE TABLE \"Crm Owner\".\"Customer\" (\"Id\" INT PRIMARY KEY,"
            + " \"FullName\" VARCHAR(50))",
        "CREATE TABLE \"Crm Owner\".account (account_id INT PRIMARY KEY, balance INT)");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
        root,
        "postgresql",
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword(),
        queries);
  }

  @Test
  void capturedRowsRestoreAfterTheyAreDeleted() throws Exception {
    // Spec: README.md > "SQL identifiers and quoting"
    // Given
    execute(
        "DELETE FROM \"Crm Owner\".account",
        "DELETE FROM \"Crm Owner\".\"Customer\"",
        "INSERT INTO \"Crm Owner\".\"Customer\" VALUES (1, 'Ana'), (2, 'Beto')",
        "INSERT INTO \"Crm Owner\".account VALUES (10, 500)");
    EngineScenario scenario = scenario(QUERIES);

    // When
    EngineScenario.Result captured = scenario.capture();
    execute("DELETE FROM \"Crm Owner\".account", "DELETE FROM \"Crm Owner\".\"Customer\"");
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    assertThat(scenario.readDataset()).contains("<Customer").contains("FullName=\"Ana\"");
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT \"FullName\" FROM \"Crm Owner\".\"Customer\" WHERE \"Id\" = 2"))
        .isEqualTo("Beto");
    assertThat(scalar("SELECT balance FROM \"Crm Owner\".account WHERE account_id = 10"))
        .isEqualTo("500");
  }

  @Test
  void prepareUpdatesExistingRowsAndInsertsMissingOnesWithBatchedUpserts() throws Exception {
    // Spec: README.md > idb-prepare (PostgreSQL applies the dataset with INSERT ... ON CONFLICT)
    // Given: row 1 exists with other values; the dataset has rows 1 and 3, and row 3 has no balance
    execute(
        "DELETE FROM \"Crm Owner\".account",
        "DELETE FROM \"Crm Owner\".\"Customer\"",
        "INSERT INTO \"Crm Owner\".\"Customer\" VALUES (1, 'Old'), (9, 'Untouched')");
    EngineScenario scenario = scenario(QUERIES);
    java.nio.file.Files.writeString(
        scenario.dataset(),
        "<dataset><Customer Id=\"1\" FullName=\"New\"/><Customer Id=\"3\" FullName=\"Cy\"/>"
            + "<account account_id=\"10\"/></dataset>");

    // When
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(prepared.out()).contains("(optimized)");
    assertThat(scalar("SELECT \"FullName\" FROM \"Crm Owner\".\"Customer\" WHERE \"Id\" = 1"))
        .isEqualTo("New");
    assertThat(scalar("SELECT \"FullName\" FROM \"Crm Owner\".\"Customer\" WHERE \"Id\" = 3"))
        .isEqualTo("Cy");
    assertThat(scalar("SELECT \"FullName\" FROM \"Crm Owner\".\"Customer\" WHERE \"Id\" = 9"))
        .isEqualTo("Untouched");
    assertThat(scalar("SELECT balance FROM \"Crm Owner\".account WHERE account_id = 10")).isNull();
  }

  @Test
  void aSchemaWrittenWithTheWrongCaseIsRejected() throws Exception {
    // Spec: README.md > "SQL identifiers and quoting" (object names are never case-folded)
    // Given
    EngineScenario scenario = scenario(QUERIES.replace("schema: Crm Owner", "schema: crm owner"));

    // When
    EngineScenario.Result captured = scenario.capture();

    // Then
    assertThat(captured.exitCode()).isEqualTo(1);
    assertThat(scenario.dataset()).doesNotExist();
  }
}
