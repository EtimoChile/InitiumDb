package cl.etimo.initiumdb.integration.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.EngineScenario;
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
import org.testcontainers.oracle.OracleContainer;

/**
 * Contract: README.md > "Hook variables", against a disposable Oracle: every supported type written
 * as a literal and as a bind variable, and a sequence moved past the applied keys, which Oracle
 * only accepts as a literal.
 */
@Tag("oracle_integration")
class OracleHookVariablesTest {

  private static final OracleContainer ORACLE =
      new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
          .withStartupTimeout(Duration.ofMinutes(6));

  private static final String QUERIES =
      "schema: APP\n"
          + "tables:\n"
          + "  - name: CUSTOMER\n"
          + "    sql: SELECT * FROM CUSTOMER ORDER BY CUSTOMER_ID\n";

  /** Every supported type, read from the applied rows. */
  private static final String SET =
      "-- idb:set SELECT MAX(CUSTOMER_ID) AS max_id, MAX(BIG_ID) AS big_id,"
          + " MAX(AMOUNT) AS amount, MAX(RATE) AS rate, MAX(NAME) AS name, MAX(CODE) AS code,"
          + " MAX(LOCAL_NAME) AS local_name, MAX(LOCAL_CODE) AS local_code, MAX(BORN) AS born,"
          + " MAX(CREATED) AS created, 'APP.CUSTOMER_SEQ' AS seq FROM APP.CUSTOMER\n";

  private static final String LOG_ROW =
      "SELECT ID || '|' || BIG_ID || '|' || TO_CHAR(AMOUNT, 'TM9', 'NLS_NUMERIC_CHARACTERS=.,')"
          + " || '|' || TO_CHAR(RATE, 'TM9', 'NLS_NUMERIC_CHARACTERS=.,') || '|' || NAME || '|'"
          + " || CODE || '|' || LOCAL_NAME || '|' || LOCAL_CODE || '|'"
          + " || TO_CHAR(BORN, 'YYYY-MM-DD HH24:MI:SS') || '|'"
          + " || TO_CHAR(CREATED, 'YYYY-MM-DD HH24:MI:SS.FF6') FROM APP.HOOK_LOG WHERE ID = ";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    ORACLE.start();
    execute(
        "CREATE USER APP IDENTIFIED BY \"App#12345\" QUOTA UNLIMITED ON USERS",
        "CREATE SEQUENCE APP.CUSTOMER_SEQ",
        "CREATE TABLE APP.CUSTOMER (CUSTOMER_ID NUMBER(10) PRIMARY KEY, BIG_ID NUMBER(19),"
            + " AMOUNT NUMBER(12,4), RATE FLOAT, NAME VARCHAR2(40), CODE CHAR(5),"
            + " LOCAL_NAME NVARCHAR2(40), LOCAL_CODE NCHAR(3), BORN DATE, CREATED TIMESTAMP(6))",
        "CREATE TABLE APP.HOOK_LOG (ID NUMBER(19), BIG_ID NUMBER(19), AMOUNT NUMBER(12,4),"
            + " RATE FLOAT, NAME VARCHAR2(40), CODE CHAR(5), LOCAL_NAME NVARCHAR2(40),"
            + " LOCAL_CODE NCHAR(3), BORN DATE, CREATED TIMESTAMP(6))",
        "CREATE TABLE APP.FLOATS (ID NUMBER(19), BD BINARY_DOUBLE, BF BINARY_FLOAT)");
  }

  @AfterAll
  static void stopDatabase() {
    ORACLE.stop();
  }

  @BeforeEach
  void cleanUp() throws SQLException {
    execute(
        "DELETE FROM APP.HOOK_LOG",
        "DELETE FROM APP.FLOATS",
        "DELETE FROM APP.CUSTOMER",
        "INSERT INTO APP.CUSTOMER VALUES (41, 9007199254740993, -1234.5, 0.000125,"
            + " 'O''Brien', 'ab', N'ñandú', N'ñu',"
            + " TO_DATE('2026-02-28 13:45:07', 'YYYY-MM-DD HH24:MI:SS'),"
            + " TIMESTAMP '2026-11-27 13:45:07.120001')");
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

  private EngineScenario scenario() throws Exception {
    EngineScenario scenario =
        new EngineScenario(
            root, "oracle", ORACLE.getJdbcUrl(), "system", ORACLE.getPassword(), QUERIES);
    EngineScenario.Result captured = scenario.capture();
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    return scenario;
  }

  @Test
  void everySupportedTypeIsWrittenTheSameAsALiteralAndAsABind() throws Exception {
    // Spec: README.md > Hook variables (supported types per engine; :name binds any type)
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        SET
            // the column itself, not MAX(), so the value keeps the column's FLOAT metadata
            + "-- idb:set SELECT RATE AS rate, BORN AS born FROM APP.CUSTOMER\n"
            + "INSERT INTO APP.HOOK_LOG VALUES (1, ${big_id}, ${amount}, ${rate}, ${name},"
            + " ${code}, ${local_name}, ${local_code}, ${born}, ${created});\n"
            + "INSERT INTO APP.HOOK_LOG VALUES (2, :big_id, :amount, :rate, :name, :code,"
            + " :local_name, :local_code, :born, :created);\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then: DATE keeps its time of day, CHAR its padding
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    String expected =
        "|9007199254740993|-1234.5|.000125|O'Brien|ab   |ñandú|ñu |2026-02-28 13:45:07"
            + "|2026-11-27 13:45:07.120001";
    assertThat(scalar(LOG_ROW + "1")).isEqualTo("1" + expected);
    assertThat(scalar(LOG_ROW + "2")).isEqualTo("2" + expected);
  }

  @Test
  void aSequenceMovesPastTheAppliedKeysThroughAnIdentifierVariable() throws Exception {
    // Spec: README.md > Hook variables (ALTER SEQUENCE needs a literal on Oracle)
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook("post", SET + "ALTER SEQUENCE $${seq} RESTART START WITH ${max_id};\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT APP.CUSTOMER_SEQ.NEXTVAL FROM DUAL")).isEqualTo("41");
  }

  @Test
  void binaryFloatingPointValuesBindAsThemselvesIncludingNull() throws Exception {
    // Spec: README.md > Hook variables (:name binds with the type of its column, NULL included)
    // Given: values a NUMBER cannot hold
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "-- idb:set SELECT 1E300D AS big, BINARY_DOUBLE_NAN AS nan,"
            + " BINARY_FLOAT_INFINITY AS inf, CAST(NULL AS BINARY_DOUBLE) AS no_value FROM DUAL\n"
            + "INSERT INTO APP.FLOATS VALUES (1, :big, :inf);\n"
            + "INSERT INTO APP.FLOATS VALUES (2, :nan, NULL);\n"
            + "INSERT INTO APP.FLOATS VALUES (3, :no_value, NULL);\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM APP.FLOATS WHERE (ID = 1 AND BD = 1E300D"
                    + " AND BF = BINARY_FLOAT_INFINITY) OR (ID = 2 AND BD IS NAN)"
                    + " OR (ID = 3 AND BD IS NULL)"))
        .isEqualTo("3");
  }

  @Test
  void referencesInsideAlternativeQuotesAreLeftAsWritten() throws Exception {
    // Spec: README.md > Hook variables (nothing is replaced inside string literals)
    // Given: a quote inside q'[...]' does not end the literal
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "-- idb:run-if SELECT 1 FROM DUAL WHERE q'[it's :missing]' IS NOT NULL\n"
            + "INSERT INTO APP.HOOK_LOG (ID, NAME) VALUES (7, q'[it''s :missing]');\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT NAME FROM APP.HOOK_LOG WHERE ID = 7")).isEqualTo("it''s :missing");
  }

  @Test
  void nullValuesBindAsNullButCannotBeWrittenAsLiterals() throws Exception {
    // Spec: README.md > Hook variables (NULL: bind it; ${name} fails)
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "-- idb:set SELECT MAX(NAME) AS nothing FROM APP.CUSTOMER WHERE CUSTOMER_ID = 0\n"
            + "INSERT INTO APP.HOOK_LOG (ID, NAME) VALUES (5, :nothing);\n"
            + "INSERT INTO APP.HOOK_LOG (ID, NAME) VALUES (6, ${nothing});\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.err()).contains("post hook statement 2").contains("NOTHING is NULL");
    assertThat(scalar("SELECT COUNT(*) FROM APP.HOOK_LOG WHERE ID = 5 AND NAME IS NULL"))
        .isEqualTo("1");
  }
}
