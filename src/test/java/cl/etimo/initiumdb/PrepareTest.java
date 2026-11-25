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
    fx.write("CRM.dataset.xml", DATASET);
  }

  @Test
  void prepareReportsRowsPerTableAndKeepsColumnsThatAppearAfterTheFirstRow() {
    // Spec: README.md > idb-prepare (progress per table; null values are absent in Flat XML)
    // Given: the first row has no STATUS, the second does
    fx.write(
        "CRM.dataset.xml",
        "<dataset>"
            + "<CUSTOMER CUSTOMER_ID=\"1001\" NAME=\"A\"/>"
            + "<CUSTOMER CUSTOMER_ID=\"1002\" NAME=\"B\" STATUS=\"VIP\"/>"
            + "<ACCOUNT ACCOUNT_ID=\"2001\" CUSTOMER_ID=\"1001\" BALANCE=\"5\"/>"
            + "</dataset>");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.value("SELECT STATUS FROM CUSTOMER WHERE CUSTOMER_ID = 1002")).isEqualTo("VIP");
    assertThat(run.out()).contains("CUSTOMER: 2 row(s)").contains("ACCOUNT: 1 row(s)");
  }

  @Test
  void prepareReportsTimesForTablesDatasetAndStep() {
    // Spec: README.md > idb-prepare (timing in the progress output)
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(run.out())
        .containsPattern("CUSTOMER: 2 row\\(s\\) in \\d+\\.\\d\\d s")
        .containsPattern("dataset: 3 row\\(s\\) in \\d+\\.\\d\\d s \\((dbunit|optimized)\\)")
        .containsPattern("prepared from .* in \\d+\\.\\d\\d s");
  }

  @Test
  void refreshDbunitForcesTheOriginalOperationAndGivesTheSameResult() {
    // Spec: README.md > idb-prepare (--refresh optimized|dbunit)
    // Given
    fx.sql("INSERT INTO CUSTOMER VALUES (1001, 'Old', 'INACTIVE')");
    String[] args = java.util.Arrays.copyOf(fx.args(), fx.args().length + 2);
    args[args.length - 2] = "--refresh";
    args[args.length - 1] = "dbunit";
    // When
    CliRun run = CliRun.prepare(fx.env, fx.home, args);
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(run.out()).contains("(dbunit)");
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1001")).isEqualTo("New One");
  }

  @Test
  void refreshRejectsUnknownValues() {
    // Spec: README.md > idb-prepare (--refresh optimized|dbunit)
    // Given
    String[] args = java.util.Arrays.copyOf(fx.args(), fx.args().length + 2);
    args[args.length - 2] = "--refresh";
    args[args.length - 1] = "fast";
    // When
    CliRun run = CliRun.prepare(fx.env, fx.home, args);
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--refresh").contains("optimized or dbunit");
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
        "CRM.dataset.xml",
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
    fx.write("CRM.pre.sql", "UPDATE CUSTOMER SET STATUS = 'PRE' WHERE CUSTOMER_ID = 1001;");
    fx.write("CRM.post.sql", "UPDATE CUSTOMER SET STATUS = 'POST' WHERE CUSTOMER_ID = 1002;");
    // When
    CliRun run = fx.prepare();
    // Then: pre ran first (dataset then overwrote its effect); post ran last
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT STATUS FROM CUSTOMER WHERE CUSTOMER_ID = 1001"))
        .isEqualTo("ACTIVE");
    assertThat(fx.value("SELECT STATUS FROM CUSTOMER WHERE CUSTOMER_ID = 1002")).isEqualTo("POST");
  }

  @Test
  void emptyAndCommentOnlyHooksAreAccepted() {
    // Spec: README.md > idb-prepare (SQL hooks; idb-init creates them empty)
    // Given
    fx.write("CRM.pre.sql", "");
    fx.write("CRM.post.sql", "-- nothing to run yet\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void sqlHooksSupportSemicolonsInsideLiteralsAndComments() {
    // Spec: README.md > idb-prepare (SQL hooks)
    // Given
    fx.write(
        "CRM.post.sql",
        "-- a comment; with a semicolon\n"
            + "UPDATE CUSTOMER SET NAME = 'a;b' /* x; y */ WHERE CUSTOMER_ID = 1001;\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1001")).isEqualTo("a;b");
  }

  private static List<String> plain(String script) {
    return SqlScript.statements(script).stream().map(SqlScript.Statement::sql).toList();
  }

  @Test
  void sqlScriptParsesConditionalDirectivesAndKeepsOtherComments() {
    // Spec: README.md > Conditional statements
    var statements =
        SqlScript.statements(
            "-- note\n-- idb:skip-if SELECT 1 FROM t WHERE a = 'x;y';\n/* hint */ INSERT INTO t VALUES (1);\n"
                + "-- idb:run-if   SELECT 2 FROM t\nDELETE FROM t;\nDROP TABLE t;");
    assertThat(statements).hasSize(3);
    assertThat(statements.get(0).sql()).isEqualTo("-- note\n\n/* hint */ INSERT INTO t VALUES (1)");
    assertThat(statements.get(0).guard())
        .isEqualTo(new SqlScript.Guard(false, "SELECT 1 FROM t WHERE a = 'x;y'"));
    assertThat(statements.get(1).sql()).isEqualTo("DELETE FROM t");
    assertThat(statements.get(1).guard()).isEqualTo(new SqlScript.Guard(true, "SELECT 2 FROM t"));
    assertThat(statements.get(2).guard()).isNull();
    // other idb-looking text is only a directive as a line comment starting with idb:
    assertThat(SqlScript.statements("/* idb:run-if x */ SELECT 1;").get(0).guard()).isNull();
    assertThat(SqlScript.statements("SELECT 'idb:run-if x';").get(0).guard()).isNull();
  }

  @Test
  void sqlScriptRejectsMalformedDirectives() {
    // Spec: README.md > Conditional statements (a typo must not run a statement unconditionally)
    for (String bad :
        List.of(
            "-- idb:runif SELECT 1\nSELECT 2;",
            "-- idb:run-if\nSELECT 2;",
            "-- idb:run-if SELECT 1\n-- idb:skip-if SELECT 1\nSELECT 2;",
            "-- idb:run-if SELECT 1\n;SELECT 2;",
            "-- idb:run-if SELECT 1\n",
            "SELECT 2 -- idb:run-if SELECT 1\n;")) {
      assertThatThrownBy(() -> SqlScript.statements(bad))
          .as(bad)
          .isInstanceOf(InitiumDbException.class);
    }
  }

  @Test
  void sqlScriptSplitsStatementsAndRejectsUnterminatedLiterals() {
    // Spec: README.md > idb-prepare (SQL hooks)
    assertThat(plain("SELECT 1; ;SELECT 'a;b';\n-- end;"))
        .isEqualTo(List.of("SELECT 1", "SELECT 'a;b'"));
    assertThatThrownBy(() -> plain("SELECT 'oops;")).isInstanceOf(InitiumDbException.class);
  }

  @Test
  void sqlScriptKeepsCommentsInsideStatementsAndDropsCommentOnlyChunks() {
    // Spec: README.md > idb-prepare (SQL hooks keep their comments, e.g. optimizer hints)
    assertThat(
            plain(
                "-- header; kept\nSELECT /*+ FIRST_ROWS(1) */ 1 FROM t; -- tail\n/* only a comment */"))
        .isEqualTo(List.of("-- header; kept\nSELECT /*+ FIRST_ROWS(1) */ 1 FROM t"));
    assertThat(plain("-- nothing\n/* here */\n")).isEmpty();
  }

  @Test
  void sqlScriptKeepsSemicolonsAndEscapedQuotesInsideQuotedIdentifiers() {
    // Spec: README.md > idb-prepare (SQL hooks); "SQL identifiers and quoting"
    assertThat(plain("SELECT \"a;b\" FROM t; SELECT \"x\"\";y\" FROM t;"))
        .isEqualTo(List.of("SELECT \"a;b\" FROM t", "SELECT \"x\"\";y\" FROM t"));
    assertThatThrownBy(() -> plain("SELECT \"oops;"))
        .isInstanceOf(InitiumDbException.class)
        .hasMessageContaining("quoted identifier");
  }

  @Test
  void aQuotedIdentifierWithASemicolonWorksInAHook() {
    // Spec: README.md > idb-prepare (SQL hooks)
    // Given: a column alias containing a semicolon, inside a statement
    fx.write(
        "CRM.post.sql",
        "UPDATE CUSTOMER SET NAME = (SELECT MAX(NAME) AS \"a;b\" FROM CUSTOMER)"
            + " WHERE CUSTOMER_ID = 1001;\n");
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
    fx.write(
        "CRM.post.sql", "-- first a comment\n/* and a block */\nUPDATE NO_SUCH_TABLE SET X = 1;");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("post hook statement 1 failed").contains("UPDATE NO_SUCH_TABLE");
    assertThat(run.err()).contains("[SQL: UPDATE NO_SUCH_TABLE SET X = 1]");
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void aFailingDatasetRollsBackAllItsRowsButKeepsPreSql() {
    // Spec: README.md > idb-prepare (dataset is all or nothing; hooks are not rolled back)
    // Given: preSql inserts a row; the dataset's second table has an unknown column
    fx.write("CRM.pre.sql", "INSERT INTO CUSTOMER VALUES (9000, 'Pre', 'ACTIVE');");
    fx.write(
        "CRM.dataset.xml",
        "<dataset>\n"
            + "  <CUSTOMER CUSTOMER_ID=\"1001\" NAME=\"New One\" STATUS=\"ACTIVE\"/>\n"
            + "  <ACCOUNT NO_SUCH_COLUMN=\"1\"/>\n"
            + "</dataset>\n");
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
    // Given: alias 1 is valid, alias 2 fails
    fx.write("BILLING.dataset.xml", DATASET);
    fx.write("BILLING.post.sql", "UPDATE NO_SUCH_TABLE SET X = 1;");
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n"
            + "  CRM:\n    engine: postgresql\n    jdbcUrl: \""
            + fx.url
            + "\"\n    username: \"sa\"\n"
            + "  BILLING:\n    engine: postgresql\n    credential: CRM\n    jdbcUrl: \""
            + fx.url
            + "\"\n    username: \"sa\"\n");
    fx.writeCase("version: 1\ndatabases:\n  - alias: CRM\n  - alias: BILLING\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.out()).contains("Alias CRM (dev) 1 of 2: prepared");
    assertThat(run.err()).contains("Alias BILLING (dev) 2 of 2");
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void prepareFailsWhenTheDatasetFileIsMissing() throws IOException {
    // Spec: README.md > Errors, repeatability and observability
    // Given
    Files.delete(fx.path("CRM.dataset.xml"));
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Dataset not found");
  }

  @Test
  void conditionalStatementsRunOnlyWhenTheirGuardAllows() {
    // Spec: README.md > Conditional statements (-- idb:run-if / -- idb:skip-if)
    // Given: the dataset leaves CUSTOMER 1001 present and 1002 absent; hooks are non-idempotent
    fx.write(
        "CRM.post.sql",
        "-- idb:run-if SELECT 1 FROM CUSTOMER WHERE CUSTOMER_ID = 1001\n"
            + "INSERT INTO ACCOUNT VALUES (3001, 1001, 1);\n"
            + "-- idb:run-if SELECT 1 FROM CUSTOMER WHERE CUSTOMER_ID = 4242\n"
            + "INSERT INTO ACCOUNT VALUES (3002, 1001, 1);\n"
            + "-- idb:skip-if SELECT 1 FROM ACCOUNT WHERE ACCOUNT_ID = 3001;\n"
            + "INSERT INTO ACCOUNT VALUES (3003, 1001, 1);\n"
            + "-- idb:skip-if SELECT 1 FROM CUSTOMER WHERE CUSTOMER_ID = 4242\n"
            + "INSERT INTO ACCOUNT VALUES (3004, 1001, 1);\n"
            + "INSERT INTO ACCOUNT VALUES (3005, 1001, 1);\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(
            fx.value(
                "SELECT LISTAGG(ACCOUNT_ID, ',') WITHIN GROUP (ORDER BY ACCOUNT_ID)"
                    + " FROM ACCOUNT WHERE ACCOUNT_ID >= 3000"))
        .isEqualTo("3001,3004,3005");
  }

  @Test
  void aMalformedOrDanglingDirectiveFailsBeforeAnythingRuns() {
    // Spec: README.md > Conditional statements (a typo must never run a statement unconditionally)
    // Given
    fx.write("CRM.pre.sql", "INSERT INTO CUSTOMER VALUES (9000, 'Pre', 'ACTIVE');");
    fx.write(
        "CRM.post.sql", "-- idb:runif SELECT 1\nINSERT INTO CUSTOMER VALUES (9001, 'P', 'X');\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Unknown directive 'idb:runif'");
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("0");
  }

  @Test
  void prepareUsesThePasswordFromTheEncryptedSecretsFile() {
    // Spec: README.md > Local secrets
    // Given: the password only lives, encrypted, in the secrets file
    fx.write("../cfg/dev.secrets.json", "{\"CRM\": \"" + Fixture.PASSWORD + "\"}");
    CliRun crypt =
        CliRun.crypt(
            Map.of(), fx.home, "--environment", "dev", "--config-dir", fx.configDir.toString());
    // When
    CliRun run = CliRun.prepare(Map.of(), fx.home, fx.prepareArgs());
    // Then
    assertThat(crypt.exitCode()).isZero();
    assertThat(run.exitCode()).isZero();
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER")).isEqualTo("2");
  }

  @Test
  void prepareEncryptsCleartextSecretsOnTheFlyAndStillConnects() throws Exception {
    // Spec: README.md > Local secrets (cleartext values are encrypted when a command finds them)
    // Given
    fx.write("../cfg/dev.secrets.json", "{\"CRM\": \"" + Fixture.PASSWORD + "\"}");
    // When
    CliRun run = CliRun.prepare(Map.of(), fx.home, fx.prepareArgs());
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.read("../cfg/dev.secrets.json"))
        .doesNotContain("\"" + Fixture.PASSWORD + "\"")
        .contains("enc:v1:");
    assertThat(run.err()).contains("encrypted");
  }

  @Test
  void prepareFailsWithoutCreatingAMasterKeyWhenSecretsAreEncryptedButNoKeyExists() {
    // Spec: README.md > Local secrets
    // Given
    String token = SecretCipher.encrypt(Fixture.PASSWORD, new byte[32]);
    fx.write("../cfg/dev.secrets.json", "{\"CRM\": \"" + token + "\"}");
    // When
    CliRun run = CliRun.prepare(Map.of(), fx.home, fx.prepareArgs());
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
