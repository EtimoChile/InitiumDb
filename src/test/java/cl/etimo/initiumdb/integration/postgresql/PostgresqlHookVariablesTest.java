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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contract: README.md > "Hook variables", against a disposable PostgreSQL: every supported type
 * written as a literal and as a bind variable, identifiers, and a sequence moved past the applied
 * keys.
 */
@Tag("postgresql_integration")
class PostgresqlHookVariablesTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final String QUERIES =
      "schema: app\n"
          + "tables:\n"
          + "  - name: customer\n"
          + "    sql: SELECT * FROM customer ORDER BY customer_id\n";

  /** Every supported type, read from the applied rows. */
  private static final String SET =
      "-- idb:set SELECT max(customer_id) AS max_id, max(big_id) AS big_id,"
          + " max(amount) AS amount, max(name) AS name, max(code) AS code, max(born) AS born,"
          + " max(created) AS created, bool_and(active) AS active, max(status) AS status,"
          + " max(created_tz) AS created_tz, 'app.customer_seq' AS seq FROM app.customer\n";

  private static final String LOG_ROW =
      "SELECT id || '|' || big_id || '|' || amount || '|' || name || '|' || code || '|' || born"
          + " || '|' || created || '|' || active || '|' || status FROM app.hook_log WHERE id = ";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    POSTGRES.start();
    execute(
        "CREATE SCHEMA app",
        "CREATE TYPE app.status AS ENUM ('ACTIVE', 'CLOSED')",
        "CREATE SEQUENCE app.customer_seq",
        "CREATE TABLE app.customer (customer_id integer PRIMARY KEY, big_id bigint,"
            + " amount numeric(12,4), name varchar(40), code char(5), born date,"
            + " created timestamp(6), active boolean, status app.status,"
            + " created_tz timestamptz)",
        "CREATE TABLE app.hook_log (id bigint, big_id bigint, amount numeric(12,4),"
            + " name varchar(40), code char(5), born date, created timestamp(6),"
            + " active boolean, status app.status, created_tz timestamptz, at time(6),"
            + " flag bit(1), bits varbit)");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void cleanUp() throws SQLException {
    execute(
        "DELETE FROM app.hook_log",
        "DELETE FROM app.customer",
        "INSERT INTO app.customer VALUES (41, 9007199254740993, -1234.5000, 'O''Brien',"
            + " 'ab', DATE '2026-02-28', TIMESTAMP '2026-11-27 13:45:07.120001', true,"
            + " 'ACTIVE', TIMESTAMPTZ '2026-11-27 13:45:07+03')");
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

  private EngineScenario scenario() throws Exception {
    EngineScenario scenario =
        new EngineScenario(
            root,
            "postgresql",
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            QUERIES);
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
            + "INSERT INTO app.hook_log VALUES (1, ${big_id}, ${amount}, ${name}, ${code},"
            + " ${born}, ${created}, ${active}, ${status}, NULL);\n"
            + "INSERT INTO app.hook_log VALUES (2, :big_id, :amount, :name, :code, :born,"
            + " :created, :active, :status, :created_tz);\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    String expected =
        "|9007199254740993|-1234.5000|O'Brien|ab|2026-02-28|2026-11-27 13:45:07.120001|true|ACTIVE";
    // char(n) to text drops the padding, so compare the stored code too
    assertThat(scalar("SELECT count(*) FROM app.hook_log WHERE code = 'ab   '")).isEqualTo("2");
    assertThat(scalar(LOG_ROW + "1")).isEqualTo("1" + expected);
    assertThat(scalar(LOG_ROW + "2")).isEqualTo("2" + expected);
    assertThat(
            scalar(
                "SELECT count(*) FROM app.hook_log l JOIN app.customer c"
                    + " ON l.created_tz = c.created_tz WHERE l.id = 2"))
        .isEqualTo("1");
  }

  @Test
  void enumValuesBindWithTheirColumnTypeIncludingNull() throws Exception {
    // Spec: README.md > Hook variables (:name binds with the type of its column, NULL included)
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "-- idb:set SELECT status AS saved_status FROM app.customer WHERE customer_id = 41\n"
            + "UPDATE app.customer SET status = 'CLOSED';\n"
            + "UPDATE app.customer SET status = :saved_status WHERE customer_id = 41;\n"
            + "-- idb:set SELECT max(status) AS no_status FROM app.customer WHERE customer_id = 0\n"
            + "INSERT INTO app.hook_log (id, status) VALUES (7, :no_status);\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT status FROM app.customer WHERE customer_id = 41"))
        .isEqualTo("ACTIVE");
    assertThat(scalar("SELECT count(*) FROM app.hook_log WHERE id = 7 AND status IS NULL"))
        .isEqualTo("1");
  }

  @Test
  void backslashesAndTimeFractionsAreKeptWhateverTheSessionSettings() throws Exception {
    // Spec: README.md > Hook variables (text literals do not depend on session settings;
    // :name binds keep the value)
    // Given: a value read with standard strings, written back with them off
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "-- idb:set SELECT 'C:\\tmp\\' AS path, TIME '12:34:56.123456' AS at\n"
            + "SET standard_conforming_strings = off;\n"
            + "INSERT INTO app.hook_log (id, name, at) VALUES (8, ${path}, :at);\n"
            + "SET standard_conforming_strings = on;\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT name || '|' || at FROM app.hook_log WHERE id = 8"))
        .isEqualTo("C:\\tmp\\|12:34:56.123456");
  }

  @Test
  void bitStringsBindWithTheirColumnTypeIncludingNull() throws Exception {
    // Spec: README.md > Hook variables (:name binds with the type of its column, NULL included)
    // Given: pgjdbc reports bit(1) as a boolean
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "-- idb:set SELECT B'1' AS one_bit, B'0101'::varbit AS bits, NULL::bit(1) AS no_bit\n"
            + "INSERT INTO app.hook_log (id, flag, bits) VALUES (9, :one_bit, :bits);\n"
            + "INSERT INTO app.hook_log (id, flag) VALUES (10, :no_bit);\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT flag::text || '|' || bits::text FROM app.hook_log WHERE id = 9"))
        .isEqualTo("1|0101");
    assertThat(scalar("SELECT count(*) FROM app.hook_log WHERE id = 10 AND flag IS NULL"))
        .isEqualTo("1");
  }

  @Test
  void referencesInsideEscapeStringsAreLeftAsWritten() throws Exception {
    // Spec: README.md > Hook variables (nothing is replaced inside string literals)
    // Given: an escaped quote in E'...' does not end the literal (guards are not split, so they
    // may use it; statements use a doubled quote)
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "-- idb:run-if SELECT 1 WHERE E'it\\'s :missing ${missing}' IS NOT NULL\n"
            + "INSERT INTO app.hook_log (id, name) VALUES (11, E'it''s :missing');\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT name FROM app.hook_log WHERE id = 11")).isEqualTo("it's :missing");
  }

  @Test
  void referencesInsideDollarQuotesAndNestedCommentsAreLeftAsWritten() throws Exception {
    // Spec: README.md > Hook variables (nothing is replaced inside string literals or comments)
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        "INSERT INTO app.hook_log (id, name) VALUES (12, $$a :missing ${missing}$$);\n"
            + "INSERT INTO app.hook_log (id, name) VALUES (13, $q$b :missing$q$)"
            + " /* outer /* inner */ :missing */;\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT string_agg(name, '|' ORDER BY id) FROM app.hook_log"))
        .isEqualTo("a :missing ${missing}|b :missing");
  }

  @Test
  void aSequenceMovesPastTheAppliedKeysThroughAnIdentifierVariable() throws Exception {
    // Spec: README.md > Hook variables ($${name} writes an identifier; ${name} a number)
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook("post", SET + "ALTER SEQUENCE $${seq} RESTART WITH ${max_id};\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT nextval('app.customer_seq')")).isEqualTo("41");
  }

  @Test
  void questionMarkOperatorsWorkWithAndWithoutBindVariables() throws Exception {
    // Spec: README.md > Hook variables (write ? as ?? in a statement with bind variables)
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post",
        SET
            + "INSERT INTO app.hook_log (id) SELECT 3 WHERE '{\"a\":1}'::jsonb ? 'a';\n"
            + "INSERT INTO app.hook_log (id) SELECT :max_id WHERE '{\"a\":1}'::jsonb ?? 'a';\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT string_agg(id::text, ',' ORDER BY id) FROM app.hook_log"))
        .isEqualTo("3,41");
  }

  @Test
  void timestampsWithTimeZoneAndBitStringsCannotBeWrittenAsLiterals() throws Exception {
    // Spec: README.md > Hook variables (types outside the table fail as ${name})
    // Given
    EngineScenario scenario = scenario();
    scenario.writeHook(
        "post", SET + "INSERT INTO app.hook_log (created_tz) VALUES (${created_tz});\n");
    // When
    EngineScenario.Result prepared = scenario.prepare();
    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.err())
        .contains("CREATED_TZ has type timestamptz")
        .contains("bind it as :CREATED_TZ");
    assertThat(scalar("SELECT count(*) FROM app.hook_log")).isEqualTo("0");
  }
}
