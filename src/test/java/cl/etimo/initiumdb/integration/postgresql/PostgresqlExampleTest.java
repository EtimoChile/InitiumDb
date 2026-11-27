package cl.etimo.initiumdb.integration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.ExampleCase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contract: README.md > "Directory layout" (the ready-to-copy {@code examples/postgresql}), run end
 * to end against a disposable PostgreSQL exactly as shipped: {@code schema.sql} through {@code
 * psql}, the secrets file through {@code idb-crypt}, then {@code idb-prepare} and {@code
 * idb-capture} on {@code testdata/regression}.
 */
@Tag("postgresql_integration")
class PostgresqlExampleTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @TempDir Path root;

  private ExampleCase example;

  @BeforeAll
  static void startDatabase() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  /** Every test starts from the example's own schema, run as its comment says: with psql. */
  @BeforeEach
  void createExampleSchema() throws Exception {
    example = new ExampleCase(root, "postgresql", POSTGRES.getJdbcUrl(), POSTGRES.getUsername());
    POSTGRES.copyFileToContainer(
        Transferable.of(java.nio.file.Files.readAllBytes(example.schema())), "/tmp/schema.sql");
    ExecResult psql =
        POSTGRES.execInContainer(
            "psql",
            "-q",
            "-U",
            POSTGRES.getUsername(),
            "-d",
            POSTGRES.getDatabaseName(),
            "-f",
            "/tmp/schema.sql");
    assertThat(psql.getExitCode()).as(psql.getStderr()).isZero();
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

  @Test
  void theExamplePasswordIsEncryptedByIdbCrypt() throws Exception {
    // Spec: README.md > "Directory layout" (copy secrets.example.json, set the password, run
    // idb-crypt) and README.md > "Local secrets"
    // Given
    example.writeSecrets(POSTGRES.getPassword());

    // When
    ExampleCase.Result crypt = example.crypt();

    // Then
    assertThat(crypt.exitCode()).as(crypt.err()).isZero();
    assertThat(example.readSecrets()).doesNotContain(POSTGRES.getPassword());
    assertThat(crypt.out() + crypt.err()).doesNotContain(POSTGRES.getPassword());
  }

  @Test
  void theExampleDatasetRestoresTheCaseRowsAndKeepsOtherRows() throws Exception {
    // Spec: README.md > idb-prepare (pre hook, then the dataset in its declared table order)
    // Given: a case customer changed by a previous test run, and a customer outside the case
    execute(
        "INSERT INTO crm_owner.customer VALUES (1001, 'Changed', 'CLOSED'), (3000, 'Outside',"
            + " 'ACTIVE')",
        "INSERT INTO crm_owner.account VALUES (2001, 1001, 1), (3001, 3000, 9)");
    example.writeSecrets(POSTGRES.getPassword());
    assertThat(example.crypt().exitCode()).isZero();

    // When
    ExampleCase.Result prepared = example.prepare();

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar(
                "SELECT name || '/' || status FROM crm_owner.customer WHERE customer_id ="
                    + " 1001"))
        .isEqualTo("Demo Customer One/ACTIVE");
    assertThat(scalar("SELECT name FROM crm_owner.customer WHERE customer_id = 1002"))
        .isEqualTo("Demo Customer Two");
    assertThat(scalar("SELECT balance::int FROM crm_owner.account WHERE account_id = 2001"))
        .isEqualTo("50000");
    assertThat(scalar("SELECT customer_id FROM crm_owner.account WHERE account_id = 2002"))
        .isEqualTo("1002");
    assertThat(scalar("SELECT name FROM crm_owner.customer WHERE customer_id = 3000"))
        .isEqualTo("Outside");
    assertThat(scalar("SELECT balance::int FROM crm_owner.account WHERE account_id = 3001"))
        .isEqualTo("9");
  }

  @Test
  void theExampleQueriesCaptureOnlyTheCaseRowsAndPrepareRestoresThem() throws Exception {
    // Spec: README.md > idb-capture (queries.yml selects the rows, in table order) and
    // README.md > idb-prepare
    // Given: the case rows in the database together with a customer outside the case
    example.writeSecrets(POSTGRES.getPassword());
    assertThat(example.crypt().exitCode()).isZero();
    assertThat(example.prepare().exitCode()).isZero();
    execute(
        "UPDATE crm_owner.customer SET name = 'Captured Name' WHERE customer_id = 1002",
        "INSERT INTO crm_owner.customer VALUES (3000, 'Outside', 'ACTIVE')");

    // When
    ExampleCase.Result captured = example.capture();
    execute("DELETE FROM crm_owner.account", "DELETE FROM crm_owner.customer");
    ExampleCase.Result prepared = example.prepare();

    // Then
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    String dataset = example.readDataset();
    assertThat(dataset)
        .contains("name=\"Captured Name\"")
        .contains("account_id=\"2002\"")
        .doesNotContain("Outside");
    assertThat(dataset.indexOf("<customer")).isLessThan(dataset.indexOf("<account"));
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT count(*) FROM crm_owner.customer")).isEqualTo("2");
    assertThat(scalar("SELECT name FROM crm_owner.customer WHERE customer_id = 1002"))
        .isEqualTo("Captured Name");
    assertThat(scalar("SELECT count(*) FROM crm_owner.account")).isEqualTo("2");
  }

  @Test
  void theExampleWithItsPlaceholderPasswordFailsWithoutChangingDataOrPrintingIt() throws Exception {
    // Spec: README.md > "Directory layout" (the password must be set) and AGENTS.md > "Security
    // and data" (never print secrets)
    // Given: secrets.example.json copied without replacing the placeholder
    execute("INSERT INTO crm_owner.customer VALUES (1001, 'Changed', 'CLOSED')");
    example.writeSecrets(null);
    assertThat(example.crypt().exitCode()).isZero();

    // When
    ExampleCase.Result prepared = example.prepare();

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.out() + prepared.err())
        .doesNotContain("replace-with-the-password-then-run-idb-crypt");
    assertThat(scalar("SELECT name FROM crm_owner.customer WHERE customer_id = 1001"))
        .isEqualTo("Changed");
  }
}
