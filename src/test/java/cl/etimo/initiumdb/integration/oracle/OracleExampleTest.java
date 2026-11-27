package cl.etimo.initiumdb.integration.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.ExampleCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.oracle.OracleContainer;

/**
 * Contract: README.md > "Directory layout" (the ready-to-copy {@code examples/oracle}), run end to
 * end against a disposable Oracle exactly as shipped: {@code schema.sql} through {@code sqlplus},
 * the secrets file through {@code idb-crypt}, then {@code idb-prepare} and {@code idb-capture} on
 * {@code testdata/regression}. The {@code CRM_OWNER} schema itself is a prerequisite of the
 * example's {@code schema.sql}, created here.
 */
@Tag("oracle_integration")
class OracleExampleTest {

  private static final OracleContainer ORACLE =
      new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
          // The image takes about two minutes to open its database on a laptop; the default is 60
          // s.
          .withStartupTimeout(Duration.ofMinutes(6));

  private static final String PLACEHOLDER = "replace-with-the-password-then-run-idb-crypt";

  @TempDir Path root;

  private ExampleCase example;

  @BeforeAll
  static void startDatabase() throws SQLException {
    ORACLE.start();
    execute("CREATE USER CRM_OWNER IDENTIFIED BY \"Owner#12345\" QUOTA UNLIMITED ON USERS");
  }

  @AfterAll
  static void stopDatabase() {
    ORACLE.stop();
  }

  /** Every test starts from the example's own schema, run as its directives expect: sqlplus. */
  @BeforeEach
  void createExampleSchema() throws Exception {
    example = new ExampleCase(root, "oracle", ORACLE.getJdbcUrl(), "system");
    ORACLE.copyFileToContainer(
        Transferable.of(Files.readAllBytes(example.schema())), "/tmp/schema.sql");
    ExecResult sqlplus =
        ORACLE.execInContainer(
            "sh",
            "-c",
            "echo exit | sqlplus -S -L 'system/"
                + ORACLE.getPassword()
                + "@//localhost:1521/"
                + ORACLE.getDatabaseName()
                + "' @/tmp/schema.sql");
    assertThat(sqlplus.getExitCode()).as(sqlplus.getStdout() + sqlplus.getStderr()).isZero();
  }

  /** The disposable instance's {@code system} account runs InitiumDb. */
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

  @Test
  void theExamplePasswordIsEncryptedByIdbCrypt() throws Exception {
    // Spec: README.md > "Directory layout" (copy secrets.example.json, set the password, run
    // idb-crypt) and README.md > "Local secrets"
    // Given
    example.writeSecrets(ORACLE.getPassword());

    // When
    ExampleCase.Result crypt = example.crypt();

    // Then
    assertThat(crypt.exitCode()).as(crypt.err()).isZero();
    assertThat(example.readSecrets()).doesNotContain("\"" + ORACLE.getPassword() + "\"");
  }

  @Test
  void theExampleDatasetRestoresTheCaseRowsAndKeepsOtherRows() throws Exception {
    // Spec: README.md > idb-prepare (pre hook, then the dataset in its declared table order)
    // Given: a case customer changed by a previous test run, and a customer outside the case
    execute(
        "INSERT INTO CRM_OWNER.CUSTOMER VALUES (1001, 'Changed', 'CLOSED')",
        "INSERT INTO CRM_OWNER.CUSTOMER VALUES (3000, 'Outside', 'ACTIVE')",
        "INSERT INTO CRM_OWNER.ACCOUNT VALUES (2001, 1001, 1)",
        "INSERT INTO CRM_OWNER.ACCOUNT VALUES (3001, 3000, 9)");
    example.writeSecrets(ORACLE.getPassword());
    assertThat(example.crypt().exitCode()).isZero();

    // When
    ExampleCase.Result prepared = example.prepare();

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar("SELECT NAME || '/' || STATUS FROM CRM_OWNER.CUSTOMER WHERE CUSTOMER_ID = 1001"))
        .isEqualTo("Demo Customer One/ACTIVE");
    assertThat(scalar("SELECT NAME FROM CRM_OWNER.CUSTOMER WHERE CUSTOMER_ID = 1002"))
        .isEqualTo("Demo Customer Two");
    assertThat(scalar("SELECT BALANCE FROM CRM_OWNER.ACCOUNT WHERE ACCOUNT_ID = 2001"))
        .isEqualTo("50000");
    assertThat(scalar("SELECT CUSTOMER_ID FROM CRM_OWNER.ACCOUNT WHERE ACCOUNT_ID = 2002"))
        .isEqualTo("1002");
    assertThat(scalar("SELECT NAME FROM CRM_OWNER.CUSTOMER WHERE CUSTOMER_ID = 3000"))
        .isEqualTo("Outside");
    assertThat(scalar("SELECT BALANCE FROM CRM_OWNER.ACCOUNT WHERE ACCOUNT_ID = 3001"))
        .isEqualTo("9");
  }

  @Test
  void theExampleQueriesCaptureOnlyTheCaseRowsAndPrepareRestoresThem() throws Exception {
    // Spec: README.md > idb-capture (queries.yml selects the rows, in table order) and
    // README.md > idb-prepare
    // Given: the case rows in the database together with a customer outside the case
    example.writeSecrets(ORACLE.getPassword());
    assertThat(example.crypt().exitCode()).isZero();
    assertThat(example.prepare().exitCode()).isZero();
    execute(
        "UPDATE CRM_OWNER.CUSTOMER SET NAME = 'Captured Name' WHERE CUSTOMER_ID = 1002",
        "INSERT INTO CRM_OWNER.CUSTOMER VALUES (3000, 'Outside', 'ACTIVE')");

    // When
    ExampleCase.Result captured = example.capture();
    execute("DELETE FROM CRM_OWNER.ACCOUNT", "DELETE FROM CRM_OWNER.CUSTOMER");
    ExampleCase.Result prepared = example.prepare();

    // Then
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    String dataset = example.readDataset();
    assertThat(dataset)
        .contains("NAME=\"Captured Name\"")
        .contains("ACCOUNT_ID=\"2002\"")
        .doesNotContain("Outside");
    assertThat(dataset.indexOf("<CUSTOMER")).isLessThan(dataset.indexOf("<ACCOUNT"));
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT COUNT(*) FROM CRM_OWNER.CUSTOMER")).isEqualTo("2");
    assertThat(scalar("SELECT NAME FROM CRM_OWNER.CUSTOMER WHERE CUSTOMER_ID = 1002"))
        .isEqualTo("Captured Name");
    assertThat(scalar("SELECT COUNT(*) FROM CRM_OWNER.ACCOUNT")).isEqualTo("2");
  }

  @Test
  void theExampleWithItsPlaceholderPasswordFailsWithoutChangingDataOrPrintingIt() throws Exception {
    // Spec: README.md > "Directory layout" (the password must be set) and AGENTS.md > "Security
    // and data" (never print secrets)
    // Given: secrets.example.json copied without replacing the placeholder
    execute("INSERT INTO CRM_OWNER.CUSTOMER VALUES (1001, 'Changed', 'CLOSED')");
    example.writeSecrets(null);
    assertThat(example.crypt().exitCode()).isZero();

    // When
    ExampleCase.Result prepared = example.prepare();

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.out() + prepared.err()).doesNotContain(PLACEHOLDER);
    assertThat(scalar("SELECT NAME FROM CRM_OWNER.CUSTOMER WHERE CUSTOMER_ID = 1001"))
        .isEqualTo("Changed");
  }
}
