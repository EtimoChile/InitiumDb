package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract: README.md > "idb-capture". */
class CaptureTest {

  @TempDir Path tmp;
  Fixture fx;

  @BeforeEach
  void setUp() {
    fx = new Fixture(tmp);
    fx.sql(
        "INSERT INTO CUSTOMER VALUES (1001, 'Demo One', 'ACTIVE')",
        "INSERT INTO CUSTOMER VALUES (1002, 'Demo Two', 'ACTIVE')",
        "INSERT INTO CUSTOMER VALUES (1003, 'Not Selected', 'ACTIVE')",
        "INSERT INTO ACCOUNT VALUES (2001, 1001, 50000)");
  }

  @Test
  void capturedDatasetContainsOnlyTheSelectedRows() {
    // Spec: README.md > idb-capture (step 3-4)
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isZero();
    String dataset = fx.read("CRM.dataset.xml");
    assertThat(dataset).contains("Demo One").contains("Demo Two").doesNotContain("Not Selected");
    assertThat(dataset).contains("ACCOUNT_ID=\"2001\"");
  }

  @Test
  void capturedDatasetPreservesConfiguredTableOrder() {
    // Spec: README.md > Captured dataset (table order)
    // Given: ACCOUNT is configured before CUSTOMER
    fx.write(
        "CRM.queries.yml",
        "tables:\n  - name: ACCOUNT\n    sql: SELECT * FROM ACCOUNT\n"
            + "  - name: CUSTOMER\n    sql: SELECT * FROM CUSTOMER ORDER BY CUSTOMER_ID\n");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isZero();
    String dataset = fx.read("CRM.dataset.xml");
    assertThat(dataset.indexOf("<ACCOUNT")).isPositive().isLessThan(dataset.indexOf("<CUSTOMER"));
  }

  @Test
  void aTableWithoutSqlIsCapturedWholeAndKeepsTheConfiguredName() {
    // Spec: README.md > Capture queries (sql optional, defaults to SELECT * FROM "<name>")
    // Given
    fx.write("CRM.queries.yml", "tables:\n  - name: CUSTOMER\n  - name: ACCOUNT\n");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.read("CRM.dataset.xml")).contains("Not Selected").contains("ACCOUNT_ID=\"2001\"");
  }

  @Test
  void captureOfAnEmptyResultStillSucceeds() {
    // Spec: README.md > idb-capture
    // Given
    fx.sql("DELETE FROM ACCOUNT");
    fx.write("CRM.queries.yml", "tables:\n  - name: ACCOUNT\n    sql: SELECT * FROM ACCOUNT\n");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(fx.path("CRM.dataset.xml")).exists();
  }

  @Test
  void failedCaptureKeepsTheExistingDataset() {
    // Spec: README.md > Errors, repeatability and observability
    // Given
    fx.write("CRM.dataset.xml", "<dataset><CUSTOMER CUSTOMER_ID=\"1\"/></dataset>");
    fx.write(
        "CRM.queries.yml",
        "tables:\n  - name: CUSTOMER\n    sql: SELECT * FROM CUSTOMER\n"
            + "  - name: ACCOUNT\n    sql: SELECT * FROM NO_SUCH_TABLE\n");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(fx.read("CRM.dataset.xml"))
        .isEqualTo("<dataset><CUSTOMER CUSTOMER_ID=\"1\"/></dataset>");
    assertThat(run.err()).contains("Alias CRM (dev) 1 of 1");
    assertThat(run.err())
        .contains("Table 'ACCOUNT' query failed")
        .contains("SELECT * FROM NO_SUCH_TABLE");
  }

  @Test
  void captureRejectsNamesThatAreNotValidXmlNamesAndKeepsTheDataset() {
    // Spec: README.md > "SQL identifiers and quoting" (names must be valid XML names)
    // Given: a column with a space, and an existing good dataset
    fx.write("CRM.dataset.xml", "<dataset><CUSTOMER CUSTOMER_ID=\"1\"/></dataset>");
    fx.write(
        "CRM.queries.yml",
        "tables:\n  - name: CUSTOMER\n"
            + "    sql: SELECT CUSTOMER_ID AS \"Full Name\" FROM CUSTOMER\n");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Full Name").contains("CUSTOMER").contains("valid XML name");
    assertThat(fx.read("CRM.dataset.xml"))
        .isEqualTo("<dataset><CUSTOMER CUSTOMER_ID=\"1\"/></dataset>");
  }

  @Test
  void captureFailsWhenTheQueriesFileIsMissing() throws IOException {
    // Spec: README.md > Case configuration (queries used by idb-capture)
    // Given
    Files.delete(fx.path("CRM.queries.yml"));
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Queries file not found");
  }

  @Test
  void captureFailsForAnAliasMissingFromTheEnvironment() {
    // Spec: README.md > Environment configuration
    // Given
    fx.writeCase("version: 1\ndatabases:\n  - alias: BILLING\n");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("'BILLING'");
  }

  @Test
  void captureNeverPrintsThePasswordOrTheJdbcUrl() {
    // Spec: README.md > Connections and secrets (secrets must not appear in logs)
    // When: wrong password
    CliRun run =
        CliRun.capture(java.util.Map.of("IDB_CREDENTIAL_CRM", "p4ssw0rd-xyz"), fx.home, fx.args());
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.out() + run.err()).doesNotContain("p4ssw0rd-xyz").doesNotContain(fx.url);
  }

  @Test
  void captureExplainsWhereToPutAMissingJdbcDriverWithoutPrintingTheUrl() {
    // Spec: README.md > Installation (JDBC drivers are provided by the user)
    // Given: a JDBC URL whose driver is not on the classpath
    String url = "jdbc:nodriver://secret-host:1521/service";
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: postgresql\n    jdbcUrl: \""
            + url
            + "\"\n    username: \"sa\"\n");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("No JDBC driver").contains("'drivers' folder");
    assertThat(run.out() + run.err()).doesNotContain("secret-host");
  }

  @Test
  void captureFailsWhenNoCredentialIsAvailable() {
    // Spec: README.md > Local secrets
    // When: no env var and no secrets file
    CliRun run = CliRun.capture(java.util.Map.of(), fx.home, fx.args());
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Secrets file not found");
  }
}
