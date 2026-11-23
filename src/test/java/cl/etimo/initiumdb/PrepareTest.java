package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.core.MasterKey;
import cl.etimo.initiumdb.core.SecretCipher;
import cl.etimo.initiumdb.core.SqlScript;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract: README.md > "idb-prepare". */
class PrepareTest {

  private static final String DATASET =
      "<dataset>\n"
          + "  <CUSTOMER CUSTOMER_ID=\"1001\" NAME=\"New One\" STATUS=\"ACTIVE\"/>\n"
          + "  <CUSTOMER CUSTOMER_ID=\"1002\" NAME=\"New Two\" STATUS=\"ACTIVE\"/>\n"
          + "  <ACCOUNT ACCOUNT_ID=\"2001\" CUSTOMER_ID=\"1001\" BALANCE=\"50000\"/>\n"
          + "</dataset>\n";

  @TempDir Path tmp;
  Fixture fx;

  @BeforeEach
  void setUp() {
    fx = new Fixture(tmp);
    fx.write("db/CRM/dataset.xml", DATASET);
  }

  @Test
  void prepareUpdatesMatchingRowsAndInsertsMissingOnes() {
    // Spec: README.md > idb-prepare (REFRESH)
    // Given
    fx.sql("INSERT INTO CUSTOMER VALUES (1001, 'Old', 'INACTIVE')");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1001")).isEqualTo("New One");
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1002")).isEqualTo("New Two");
    assertThat(fx.value("SELECT BALANCE FROM ACCOUNT WHERE ACCOUNT_ID = 2001")).startsWith("50000");
  }

  @Test
  void prepareDoesNotDeleteRowsOutsideTheDataset() {
    // Spec: README.md > idb-prepare (REFRESH does not delete extra rows)
    // Given
    fx.sql("INSERT INTO CUSTOMER VALUES (1999, 'Extra', 'ACTIVE')");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1999")).isEqualTo("Extra");
  }

  @Test
  void prepareIsRepeatable() {
    // Spec: README.md > Errors, repeatability and observability
    // When
    CliRun first = fx.prepare();
    CliRun second = fx.prepare();
    // Then
    assertThat(first.exitCode()).isZero();
    assertThat(second.exitCode()).isZero();
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void prepareFollowsDeclaredTableOrderWithoutReorderingByDependency() {
    // Spec: README.md > Design principles (no automatic dependency discovery)
    // Given: the child table is declared before its parent
    fx.write(
        "db/CRM/dataset.xml",
        "<dataset>\n  <ACCOUNT ACCOUNT_ID=\"2001\" CUSTOMER_ID=\"1001\" BALANCE=\"1\"/>\n"
            + "  <CUSTOMER CUSTOMER_ID=\"1001\" NAME=\"N\" STATUS=\"ACTIVE\"/>\n</dataset>\n");
    // When
    CliRun run = fx.prepare();
    // Then: the database constraint fails the run and nothing is committed
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("0");
  }

  @Test
  void prepareRunsPreSqlBeforeAndPostSqlAfterTheDataset() {
    // Spec: README.md > idb-prepare (step 4)
    // Given
    fx.sql("INSERT INTO CUSTOMER VALUES (1001, 'Old', 'INACTIVE')");
    fx.write("db/CRM/pre.sql", "UPDATE CUSTOMER SET STATUS = 'PRE' WHERE CUSTOMER_ID = 1001;");
    fx.write("db/CRM/post.sql", "UPDATE CUSTOMER SET STATUS = 'POST' WHERE CUSTOMER_ID = 1002;");
    fx.writeCase(
        "version: 1\nname: t\ndatabases:\n  - alias: CRM\n    dataset: db/CRM/dataset.xml\n"
            + "    preSql: db/CRM/pre.sql\n    postSql: db/CRM/post.sql\n");
    // When
    CliRun run = fx.prepare();
    // Then: pre ran first (dataset then overwrote its effect); post ran last
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT STATUS FROM CUSTOMER WHERE CUSTOMER_ID = 1001"))
        .isEqualTo("ACTIVE");
    assertThat(fx.value("SELECT STATUS FROM CUSTOMER WHERE CUSTOMER_ID = 1002")).isEqualTo("POST");
  }

  @Test
  void sqlHooksSupportSemicolonsInsideLiteralsAndComments() {
    // Spec: README.md > idb-prepare (SQL hooks)
    // Given
    fx.write(
        "db/CRM/post.sql",
        "-- a comment; with a semicolon\n"
            + "UPDATE CUSTOMER SET NAME = 'a;b' /* x; y */ WHERE CUSTOMER_ID = 1001;\n");
    fx.writeCase(
        "version: 1\nname: t\ndatabases:\n  - alias: CRM\n    dataset: db/CRM/dataset.xml\n"
            + "    postSql: db/CRM/post.sql\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1001")).isEqualTo("a;b");
  }

  @Test
  void sqlScriptSplitsStatementsAndRejectsUnterminatedLiterals() {
    // Spec: README.md > idb-prepare (SQL hooks)
    assertThat(SqlScript.statements("SELECT 1; ;SELECT 'a;b';\n-- end;"))
        .isEqualTo(List.of("SELECT 1", "SELECT 'a;b'"));
    assertThatThrownBy(() -> SqlScript.statements("SELECT 'oops;"))
        .isInstanceOf(InitiumDbException.class);
  }

  @Test
  void sqlScriptKeepsSemicolonsAndEscapedQuotesInsideQuotedIdentifiers() {
    // Spec: README.md > idb-prepare (SQL hooks); "SQL identifiers and quoting"
    assertThat(SqlScript.statements("SELECT \"a;b\" FROM t; SELECT \"x\"\";y\" FROM t;"))
        .isEqualTo(List.of("SELECT \"a;b\" FROM t", "SELECT \"x\"\";y\" FROM t"));
    assertThatThrownBy(() -> SqlScript.statements("SELECT \"oops;"))
        .isInstanceOf(InitiumDbException.class)
        .hasMessageContaining("quoted identifier");
  }

  @Test
  void aQuotedIdentifierWithASemicolonWorksInAHook() {
    // Spec: README.md > idb-prepare (SQL hooks)
    // Given: a column alias containing a semicolon, inside a statement
    fx.write(
        "db/CRM/post.sql",
        "UPDATE CUSTOMER SET NAME = (SELECT MAX(NAME) AS \"a;b\" FROM CUSTOMER)"
            + " WHERE CUSTOMER_ID = 1001;\n");
    fx.writeCase(
        "version: 1\nname: t\ndatabases:\n  - alias: CRM\n    dataset: db/CRM/dataset.xml\n"
            + "    postSql: db/CRM/post.sql\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1001")).isEqualTo("New Two");
  }

  @Test
  void aFailingPostSqlKeepsTheDatasetAlreadyApplied() {
    // Spec: README.md > idb-prepare (hooks run in autocommit; failure stops the run)
    // Given
    fx.write("db/CRM/post.sql", "UPDATE NO_SUCH_TABLE SET X = 1;");
    fx.writeCase(
        "version: 1\nname: t\ndatabases:\n  - alias: CRM\n    dataset: db/CRM/dataset.xml\n"
            + "    postSql: db/CRM/post.sql\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void aFailingDatasetRollsBackAllItsRowsButKeepsPreSql() {
    // Spec: README.md > idb-prepare (dataset is all or nothing; hooks are not rolled back)
    // Given: preSql inserts a row; the dataset's second table has an unknown column
    fx.write("db/CRM/pre.sql", "INSERT INTO CUSTOMER VALUES (9000, 'Pre', 'ACTIVE');");
    fx.write(
        "db/CRM/dataset.xml",
        "<dataset>\n"
            + "  <CUSTOMER CUSTOMER_ID=\"1001\" NAME=\"New One\" STATUS=\"ACTIVE\"/>\n"
            + "  <ACCOUNT NO_SUCH_COLUMN=\"1\"/>\n"
            + "</dataset>\n");
    fx.writeCase(
        "version: 1\nname: t\ndatabases:\n  - alias: CRM\n    dataset: db/CRM/dataset.xml\n"
            + "    preSql: db/CRM/pre.sql\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("1");
    assertThat(fx.value("SELECT NAME FROM CUSTOMER")).isEqualTo("Pre");
  }

  @Test
  void aFailingStepStopsTheRunButEarlierStepsStayCommitted() {
    // Spec: README.md > idb-prepare (no distributed transaction)
    // Given: step 1 is valid, step 2 fails
    fx.write("db/CRM/bad.sql", "UPDATE NO_SUCH_TABLE SET X = 1;");
    fx.writeCase(
        "version: 1\nname: t\ndatabases:\n"
            + "  - alias: CRM\n    dataset: db/CRM/dataset.xml\n"
            + "  - alias: CRM\n    dataset: db/CRM/dataset.xml\n    postSql: db/CRM/bad.sql\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.out()).contains("Step 1 (CRM): prepared");
    assertThat(run.err()).contains("Step 2 (CRM)");
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void prepareFailsWhenTheDatasetFileIsMissing() throws IOException {
    // Spec: README.md > Errors, repeatability and observability
    // Given
    Files.delete(fx.path("db/CRM/dataset.xml"));
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Dataset not found");
  }

  @Test
  void prepareFailsWhenADeclaredSqlScriptIsMissing() {
    // Spec: README.md > Errors, repeatability and observability
    // Given
    fx.writeCase(
        "version: 1\nname: t\ndatabases:\n  - alias: CRM\n    dataset: db/CRM/dataset.xml\n"
            + "    preSql: db/CRM/missing.sql\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("preSql");
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("0");
  }

  @Test
  void prepareUsesThePasswordFromTheEncryptedSecretsFile() {
    // Spec: README.md > Local secrets
    // Given: the password only lives, encrypted, in the secrets file
    fx.write("../cfg/dev.secrets.json", "{\"crm\": \"" + Fixture.PASSWORD + "\"}");
    CliRun crypt =
        CliRun.crypt(
            Map.of(), fx.home, "--environment", "dev", "--config-dir", fx.configDir.toString());
    // When
    CliRun run = CliRun.prepare(Map.of(), fx.home, fx.args());
    // Then
    assertThat(crypt.exitCode()).isZero();
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void prepareRejectsACleartextSecretsFileWithoutLeakingIt() {
    // Spec: README.md > Local secrets
    // Given
    fx.write("../cfg/dev.secrets.json", "{\"crm\": \"p4ssw0rd-xyz\"}");
    // When
    CliRun run = CliRun.prepare(Map.of(), fx.home, fx.args());
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("idb-crypt");
    assertThat(run.out() + run.err()).doesNotContain("p4ssw0rd-xyz");
  }

  @Test
  void prepareFailsWithoutCreatingAMasterKeyWhenSecretsAreEncryptedButNoKeyExists() {
    // Spec: README.md > Local secrets
    // Given
    String token = SecretCipher.encrypt(Fixture.PASSWORD, new byte[32]);
    fx.write("../cfg/dev.secrets.json", "{\"crm\": \"" + token + "\"}");
    // When
    CliRun run = CliRun.prepare(Map.of(), fx.home, fx.args());
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("No master key");
    assertThat(MasterKey.keyPath(fx.home)).doesNotExist();
  }

  @Test
  void prepareRequiresTheCaseAndEnvironmentOptions() {
    // Spec: README.md > Commands
    // When
    CliRun run = CliRun.prepare(Map.of(), fx.home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--case").contains("Usage:");
  }
}
