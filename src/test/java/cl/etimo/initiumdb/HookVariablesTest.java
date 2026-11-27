package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.etimo.initiumdb.core.HookVariables;
import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.core.SqlScript;
import cl.etimo.initiumdb.db.Engines;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract: README.md > "Hook variables". */
class HookVariablesTest {

  private static final String DATASET =
      "<dataset>\n"
          + "  <CUSTOMER CUSTOMER_ID=\"1001\" NAME=\"New One\" STATUS=\"ACTIVE\"/>\n"
          + "  <CUSTOMER CUSTOMER_ID=\"1002\" NAME=\"New Two\" STATUS=\"ACTIVE\"/>\n"
          + "</dataset>\n";

  @TempDir Path tmp;
  Fixture fx;

  @BeforeEach
  void setUp() {
    fx = new Fixture(tmp);
    fx.write("CRM.dataset.xml", DATASET);
    fx.sql(
        "CREATE TABLE LOG_ROW (ID BIGINT, WHO VARCHAR(20), D DATE, TS TIMESTAMP(9),"
            + " FLAG BOOLEAN, AMOUNT DECIMAL(6,2), RATIO DOUBLE PRECISION)");
  }

  private static final String SET_ALL =
      "-- idb:set SELECT MAX(CUSTOMER_ID) AS max_id, 'O''Brien' AS Who, DATE '2026-11-27' AS d,"
          + " TIMESTAMP '2026-11-27 13:45:00.5' AS ts, TRUE AS flag,"
          + " CAST(-12.50 AS DECIMAL(6,2)) AS amount, CAST(0.25 AS DOUBLE PRECISION) AS ratio,"
          + " CAST(NULL AS VARCHAR(5)) AS nothing FROM CUSTOMER\n";

  @Test
  void literalsAndBindVariablesWriteTheValuesSetByIdbSet() {
    // Spec: README.md > Hook variables (idb:set; :name binds; ${name} literals per type)
    // Given
    fx.write(
        "CRM.post.sql",
        SET_ALL
            + "INSERT INTO LOG_ROW (ID, WHO, D, TS, FLAG, AMOUNT)"
            + " VALUES (${MAX_ID}, ${who}, ${d}, ${ts}, ${flag}, ${amount});\n"
            + "INSERT INTO LOG_ROW VALUES (:max_id + 1, :who, :d, :ts, :flag, :amount, :ratio);\n"
            + "INSERT INTO LOG_ROW (ID, WHO) VALUES (3, :nothing);\n");
    // When
    CliRun run = fx.prepare();
    // Then: both forms write the same values; a NULL binds as NULL
    assertThat(run.exitCode()).as(run.err()).isZero();
    String row = "SELECT WHO || '|' || D || '|' || TS || '|' || FLAG || '|' || AMOUNT FROM LOG_ROW";
    assertThat(fx.value(row + " WHERE ID = 1002"))
        .isEqualTo("O'Brien|2026-11-27|2026-11-27 13:45:00.5|TRUE|-12.50");
    assertThat(fx.value(row + " WHERE ID = 1003"))
        .isEqualTo("O'Brien|2026-11-27|2026-11-27 13:45:00.5|TRUE|-12.50");
    assertThat(fx.value("SELECT RATIO FROM LOG_ROW WHERE ID = 1003")).isEqualTo("0.25");
    assertThat(fx.value("SELECT COUNT(*) FROM LOG_ROW WHERE ID = 3 AND WHO IS NULL"))
        .isEqualTo("1");
    assertThat(run.out())
        .contains("post hook: set MAX_ID, WHO, D, TS, FLAG, AMOUNT, RATIO, NOTHING")
        .contains("post hook: 3 of 3 statement(s) run");
  }

  @Test
  void variablesSetInThePreHookAreUsedInThePostHookAndInGuards() {
    // Spec: README.md > Hook variables (variables live for one alias; guard queries)
    // Given: before the dataset there is one customer; after it there are two
    fx.sql("INSERT INTO CUSTOMER VALUES (1001, 'Old', 'INACTIVE')");
    fx.write("CRM.pre.sql", "-- idb:set SELECT COUNT(*) AS before FROM CUSTOMER\n");
    fx.write(
        "CRM.post.sql",
        "-- idb:run-if SELECT 1 FROM CUSTOMER HAVING COUNT(*) > :before\n"
            + "INSERT INTO LOG_ROW (ID) VALUES (${before});\n"
            + "-- idb:set SELECT COUNT(*) AS before FROM CUSTOMER\n"
            + "INSERT INTO LOG_ROW (ID) VALUES (${BEFORE});\n");
    // When
    CliRun run = fx.prepare();
    // Then: the guard compared with the pre-hook value; a later idb:set overwrote it
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.value("SELECT LISTAGG(ID, ',') WITHIN GROUP (ORDER BY ID) FROM LOG_ROW"))
        .isEqualTo("1,2");
  }

  @Test
  void identifiersAreWrittenAsIsAndInsideDoubleQuotes() {
    // Spec: README.md > Hook variables ($${name}; "$${name}" for mixed case)
    // Given
    fx.write(
        "CRM.post.sql",
        "-- idb:set SELECT 'CUSTOMER' AS tbl, 'PUBLIC.LOG_ROW' AS qualified, 'Mixed$1' AS mixed\n"
            + "UPDATE $${tbl} SET STATUS = 'X' WHERE CUSTOMER_ID = 1001;\n"
            + "INSERT INTO $${qualified} (ID) VALUES (7);\n"
            + "CREATE TABLE \"$${mixed}\" (ID INT);\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.value("SELECT STATUS FROM CUSTOMER WHERE CUSTOMER_ID = 1001")).isEqualTo("X");
    assertThat(fx.value("SELECT COUNT(*) FROM LOG_ROW WHERE ID = 7")).isEqualTo("1");
    assertThat(fx.value("SELECT COUNT(*) FROM \"Mixed$1\"")).isEqualTo("0");
  }

  @Test
  void referencesInsideLiteralsAndCommentsAreLeftAsWritten() {
    // Spec: README.md > Hook variables (nothing is replaced inside literals or comments)
    // Given: none of these variables exists, so any replacement would fail
    fx.write(
        "CRM.post.sql",
        "UPDATE CUSTOMER SET NAME = '${a} :b $${c}' /* ${d} :e */ WHERE CUSTOMER_ID = 1001"
            + " -- ${f}\n;\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.value("SELECT NAME FROM CUSTOMER WHERE CUSTOMER_ID = 1001"))
        .isEqualTo("${a} :b $${c}");
  }

  @Test
  void castsSlicesAndOtherDollarSignsAreNotReferences() {
    // Spec: README.md > Hook variables (:: casts, a[lo:hi] slices; "$${" is read before "${")
    HookVariables variables = new HookVariables(Engines.named("postgresql"));
    // When / Then: nothing is set, so a reference would fail
    assertThat(variables.expand("SELECT a::int, b[lo:hi], v$session, $$x$$ FROM t").sql())
        .isEqualTo("SELECT a::int, b[lo:hi], v$session, $$x$$ FROM t");
    assertThatThrownBy(() -> variables.expand("SELECT SYS$${X} FROM t"))
        .hasMessageContaining("Hook variable X is not defined");
  }

  @Test
  void anUndefinedVariableFailsNamingItAndTheStatementDoesNotRun() {
    // Spec: README.md > Hook variables (an unset variable fails the run, naming it)
    // Given
    fx.write(
        "CRM.post.sql",
        "UPDATE CUSTOMER SET STATUS = 'X' WHERE CUSTOMER_ID = 1001;\n"
            + "UPDATE CUSTOMER SET STATUS = 'Y' WHERE CUSTOMER_ID = ${missing};\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err())
        .contains("post hook statement 2")
        .contains("Hook variable MISSING is not defined");
    assertThat(fx.value("SELECT COUNT(*) FROM CUSTOMER WHERE STATUS = 'Y'")).isEqualTo("0");
  }

  @Test
  void variablesDoNotCarryOverToTheNextAlias() {
    // Spec: README.md > Hook variables (the next alias starts with none)
    // Given: two aliases on the same database; the first sets a variable the second uses
    fx.write("BILLING.dataset.xml", DATASET);
    fx.write("CRM.post.sql", "-- idb:set SELECT 1 AS one FROM CUSTOMER WHERE CUSTOMER_ID = 1001\n");
    fx.write("BILLING.post.sql", "INSERT INTO LOG_ROW (ID) VALUES (${one});\n");
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
    assertThat(run.err()).contains("Alias BILLING").contains("Hook variable ONE is not defined");
  }

  @Test
  void idbSetQueriesMustReturnOneRowWithValidDistinctLabels() {
    // Spec: README.md > Hook variables (exactly one row; valid labels used once)
    for (String[] bad :
        new String[][] {
          {"SELECT CUSTOMER_ID AS id FROM CUSTOMER WHERE CUSTOMER_ID = 0", "no row"},
          {"SELECT CUSTOMER_ID AS id FROM CUSTOMER", "more than one row"},
          {"SELECT MAX(CUSTOMER_ID) FROM CUSTOMER", "not a valid variable name"},
          {"SELECT 1 AS a, 2 AS A", "sets variable A twice"},
        }) {
      // Given
      fx.write("CRM.post.sql", "-- idb:set " + bad[0] + "\nINSERT INTO LOG_ROW (ID) VALUES (9);\n");
      // When
      CliRun run = fx.prepare();
      // Then: the run fails and nothing after the idb:set runs
      assertThat(run.exitCode()).as(bad[0]).isEqualTo(1);
      assertThat(run.err()).as(bad[0]).contains("post hook idb:set").contains(bad[1]);
      assertThat(fx.value("SELECT COUNT(*) FROM LOG_ROW")).isEqualTo("0");
    }
  }

  @Test
  void literalsRejectNullAndUnsupportedTypesAndIdentifiersRejectAnythingElse() {
    // Spec: README.md > Hook variables (${name}: NULL or other types fail; $${name}: identifiers)
    for (String[] bad :
        new String[][] {
          {"INSERT INTO LOG_ROW (WHO) VALUES (${nothing})", "NOTHING is NULL"},
          {"INSERT INTO LOG_ROW (RATIO) VALUES (${ratio})", "bind it as :RATIO"},
          {"INSERT INTO $${who} (ID) VALUES (1)", "WHO is not an identifier"},
          {"INSERT INTO $${max_id} (ID) VALUES (1)", "MAX_ID is not an identifier"},
          {"INSERT INTO \"$${q}\" (ID) VALUES (1)", "Q is not an unqualified identifier"},
          {"INSERT INTO $${inject} (ID) VALUES (1)", "INJECT is not an identifier"},
        }) {
      // Given
      fx.write(
          "CRM.post.sql",
          SET_ALL
              + "-- idb:set SELECT 'PUBLIC.LOG_ROW' AS q, 'LOG_ROW (ID) VALUES (1); --' AS inject\n"
              + bad[0]
              + ";\n");
      // When
      CliRun run = fx.prepare();
      // Then
      assertThat(run.exitCode()).as(bad[0]).isEqualTo(1);
      assertThat(run.err()).as(bad[0]).contains("post hook statement 1").contains(bad[1]);
      assertThat(fx.value("SELECT COUNT(*) FROM LOG_ROW")).isEqualTo("0");
    }
  }

  @Test
  void variableValuesAreNeverPrinted() {
    // Spec: README.md > Hook variables (values are never printed; errors show the hook's text)
    // Given: a value used in a statement that fails
    fx.write(
        "CRM.post.sql",
        "-- idb:set SELECT 'S3cr3t-Value' AS token, 4242 AS num FROM CUSTOMER"
            + " WHERE CUSTOMER_ID = 1001\n"
            + "INSERT INTO NO_SUCH_TABLE VALUES (${token}, :num, ${num});\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("${token}, :num, ${num}");
    assertThat(run.out() + run.err()).doesNotContain("S3cr3t").doesNotContain("4242");
  }

  @Test
  void idbSetStandsAloneBetweenStatements() {
    // Spec: README.md > Hook variables (idb:set is not attached to a statement, cannot be guarded)
    List<SqlScript.Statement> statements =
        SqlScript.statements("SELECT 1;\n-- idb:set SELECT 2 AS two;\nSELECT 3;");
    assertThat(statements)
        .extracting(SqlScript.Statement::sql, SqlScript.Statement::assignment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("SELECT 1", false),
            org.assertj.core.groups.Tuple.tuple("SELECT 2 AS two", true),
            org.assertj.core.groups.Tuple.tuple("SELECT 3", false));
    for (String bad :
        List.of(
            "-- idb:set\nSELECT 1;",
            "-- idb:run-if SELECT 1\n-- idb:set SELECT 2 AS two\nSELECT 3;",
            "SELECT 1\n-- idb:set SELECT 2 AS two\n;",
            "-- idb:sets SELECT 2 AS two\nSELECT 3;")) {
      assertThatThrownBy(() -> SqlScript.statements(bad))
          .as(bad)
          .isInstanceOf(InitiumDbException.class);
    }
  }
}
