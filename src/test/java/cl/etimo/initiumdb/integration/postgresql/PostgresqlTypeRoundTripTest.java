package cl.etimo.initiumdb.integration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.EngineScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contract: README.md > "Captured dataset" (handling of nulls and types must be verified per
 * engine), against a disposable PostgreSQL. A value is captured, its row deleted, and the dataset
 * prepared again; the value read back must equal the original exactly. Every case runs on both
 * apply paths: the batched upsert ({@code --refresh optimized}) and DBUnit's {@code REFRESH}
 * ({@code --refresh dbunit}), which tables with LOB or engine-specific columns take.
 */
@Tag("postgresql_integration")
class PostgresqlTypeRoundTripTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final String QUERIES = "schema: idb_types\ntables:\n  - name: typed\n";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    POSTGRES.start();
    execute("CREATE SCHEMA idb_types");
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

  /**
   * Captures one row holding {@code literal} in a column of {@code type}, deletes the row, prepares
   * the dataset with {@code refresh} and returns the column read back as PostgreSQL text.
   */
  private String roundTrip(String type, String literal, String refresh) throws Exception {
    return roundTrip(type, literal, "%s::text", refresh);
  }

  /** As above, with the column rendered by {@code text} ({@code %1$s} stands for the column). */
  private String roundTrip(String type, String literal, String text, String refresh)
      throws Exception {
    execute(
        "DROP TABLE IF EXISTS idb_types.typed",
        "CREATE TABLE idb_types.typed (id INT PRIMARY KEY, v " + type + ")",
        "INSERT INTO idb_types.typed VALUES (1, " + literal + ")");
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
    execute("DELETE FROM idb_types.typed");
    EngineScenario.Result prepared = scenario.prepare(refresh);
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    return scalar("SELECT " + text.formatted("v") + " FROM idb_types.typed WHERE id = 1");
  }

  private void assertRoundTrip(String type, String literal, String refresh) throws Exception {
    assertRoundTrip(type, literal, "%s::text", refresh);
  }

  private void assertRoundTrip(String type, String literal, String text, String refresh)
      throws Exception {
    // Given: one row holding the literal
    // When: captured, deleted and prepared again
    String actual = roundTrip(type, literal, text, refresh);
    // Then: the stored value is unchanged
    String expected = scalar("SELECT " + text.formatted("CAST(" + literal + " AS " + type + ")"));
    assertThat(actual).as(type + " " + literal).isEqualTo(expected);
  }

  // ---- nulls and text ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aCapturedNullStaysNull(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (Flat XML omits NULLs; declared columns apply as NULL)
    assertThat(roundTrip("TEXT", "NULL", refresh)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void anEmptyStringStaysEmptyAndIsNotTurnedIntoNull(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("TEXT", "''", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void textWithNewlinesTabsAndOuterSpacesIsKeptExactly(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    // XML attribute normalization would turn unescaped line breaks and tabs into spaces
    assertRoundTrip("TEXT", "E'  line 1\\r\\nline 2\\n\\tend  '", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void textWithXmlMarkupAndNonAsciiCharactersIsKeptExactly(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("VARCHAR(100)", "'<a href=\"x\">&amp; ''ñandú'' €</a>'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void charColumnsKeepTheirPadding(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("CHAR(5)", "'ab'", refresh);
  }

  // ---- dates and times ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void timestampsKeepEveryFractionalDigit(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("TIMESTAMP", "'2024-03-15 13:45:07.123456'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void datesAndTimesOfDayRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("DATE", "'1899-12-31'", refresh);
    assertRoundTrip("TIME", "'23:59:58'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void timestampsWithTimeZoneKeepTheirInstant(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("TIMESTAMPTZ", "'2024-03-15 13:45:07.5+05:30'", refresh);
  }

  /**
   * In America/Santiago clocks jumped from 2024-09-08 00:00 to 01:00, so 00:30 does not exist in
   * that zone. A {@code timestamp} has no zone and must not be shifted by the zone of the machine
   * that runs InitiumDb.
   */
  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aTimestampInsideTheLocalDaylightSavingGapIsNotShifted(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    TimeZone previous = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"));
    try {
      assertRoundTrip("TIMESTAMP", "'2024-09-08 00:30:00'", refresh);
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aDateWhoseMidnightDoesNotExistLocallyIsNotShifted(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    TimeZone previous = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"));
    try {
      assertRoundTrip("DATE", "'2024-09-08'", refresh);
    } finally {
      TimeZone.setDefault(previous);
    }
  }

  // ---- control characters ----

  @Test
  void textWithAControlCharacterFailsTheCaptureAndKeepsThePreviousDataset() throws Exception {
    // Spec: README.md > "Captured dataset" (XML 1.0 cannot hold most control characters)
    // Given: a previous dataset, and a row whose text holds U+0001
    execute(
        "DROP TABLE IF EXISTS idb_types.typed",
        "CREATE TABLE idb_types.typed (id INT PRIMARY KEY, v TEXT)",
        "INSERT INTO idb_types.typed VALUES (1, 'ok'), (2, E'secret\\x01value')");
    EngineScenario scenario =
        new EngineScenario(
            root,
            "postgresql",
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            QUERIES.replace(
                "name: typed\n", "name: typed\n    sql: SELECT * FROM typed ORDER BY id\n"));
    Files.writeString(scenario.dataset(), "<dataset/>");

    // When
    EngineScenario.Result captured = scenario.capture();

    // Then: the cell is named, the value is not shown, and the dataset is untouched
    assertThat(captured.exitCode()).isEqualTo(1);
    assertThat(captured.err())
        .contains("Table 'typed', column 'v', row 2 holds character U+0001")
        .doesNotContain("secret");
    assertThat(scenario.readDataset()).isEqualTo("<dataset/>");
  }

  // ---- binary and large values ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void byteaKeepsEveryByteValue(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (binary data must be verified per engine)
    assertRoundTrip(
        "BYTEA",
        "(SELECT decode(string_agg(lpad(to_hex(i), 2, '0'), '' ORDER BY i), 'hex')"
            + " FROM generate_series(0, 255) i)",
        refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void anEmptyByteaStaysEmptyAndIsNotTurnedIntoNull(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (binary data must be verified per engine)
    assertRoundTrip("BYTEA", "''::bytea", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aLargeByteaRoundTrips(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (binary data must be verified per engine)
    assertRoundTrip(
        "BYTEA",
        "decode(repeat('00ff7f80', 500000), 'hex')",
        "length(%1$s) || ':' || md5(%1$s)",
        refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aLargeTextRoundTrips(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip(
        "TEXT",
        "repeat('línea ñ' || chr(10), 300000)",
        "length(%1$s) || ':' || md5(%1$s)",
        refresh);
  }

  // ---- numbers ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void numericKeepsEveryDigitAndItsScale(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("NUMERIC(38,10)", "'-1234567890123456789012345678.0123456789'", refresh);
    assertRoundTrip("NUMERIC(12,4)", "'5.1000'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void numericWithoutPrecisionKeepsVeryLargeAndVerySmallValues(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("NUMERIC", "'123456789012345678901234567890123456789012345678901'", refresh);
    assertRoundTrip("NUMERIC", "'0.000000000000000000000000000000000001'", refresh);
    assertRoundTrip("NUMERIC", "'1E+40'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void integersKeepTheirLimits(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("SMALLINT", "-32768", refresh);
    assertRoundTrip("INTEGER", "2147483647", refresh);
    assertRoundTrip("BIGINT", "-9223372036854775808", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void floatingPointValuesKeepEveryBit(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("DOUBLE PRECISION", "0.1", refresh);
    assertRoundTrip("DOUBLE PRECISION", "'1.7976931348623157e308'", refresh);
    assertRoundTrip("DOUBLE PRECISION", "'4.9e-324'", refresh);
    assertRoundTrip("DOUBLE PRECISION", "'-0'", refresh);
    assertRoundTrip("REAL", "'3.4028235e38'", refresh);
    assertRoundTrip("REAL", "'0.1'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void floatingPointSpecialValuesRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("DOUBLE PRECISION", "'NaN'", refresh);
    assertRoundTrip("DOUBLE PRECISION", "'-Infinity'", refresh);
    assertRoundTrip("NUMERIC", "'NaN'", refresh);
  }

  // ---- PostgreSQL types ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void booleansRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("BOOLEAN", "TRUE", refresh);
    assertRoundTrip("BOOLEAN", "FALSE", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void uuidsRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("UUID", "'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void jsonKeepsItsTextAndJsonbItsValue(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    // json keeps spacing and key order; jsonb normalizes both
    String document = "'{\"b\": [1, \"<x> & é\", null],   \"a\": {\"q\": \"\\\"\"}}'";
    assertRoundTrip("JSON", document, refresh);
    assertRoundTrip("JSONB", document, refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void enumValuesRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    execute(
        "DROP TABLE IF EXISTS idb_types.typed",
        "DROP TYPE IF EXISTS idb_types.mood",
        "CREATE TYPE idb_types.mood AS ENUM ('sad', 'ok', 'happy')");
    assertRoundTrip("idb_types.mood", "'happy'", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void arraysRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("INTEGER[]", "'{1,NULL,-3}'", refresh);
    assertRoundTrip("TEXT[]", "ARRAY['a,b', 'say \"hi\"', '', NULL]", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void intervalsAndNetworkAddressesRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("INTERVAL", "'1 year 2 mons 3 days 04:05:06.789'", refresh);
    assertRoundTrip("INET", "'192.168.0.1/24'", refresh);
    assertRoundTrip("CIDR", "'10.0.0.0/8'", refresh);
  }

  // ---- generated columns ----

  /**
   * Creates the table with {@code ddl}, runs {@code insert}, captures, runs {@code change} (which
   * undoes part of the data) and prepares again with {@code refresh}; returns the prepare result.
   */
  private EngineScenario.Result applyAfterChange(
      String ddl, String insert, String change, String refresh) throws Exception {
    execute("DROP TABLE IF EXISTS idb_types.typed", ddl, insert);
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
    execute(change);
    return scenario.prepare(refresh);
  }

  private static String rows() throws SQLException {
    return scalar("SELECT string_agg(t::text, ' ' ORDER BY t.id) FROM idb_types.typed t");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void identityColumnsGeneratedByDefaultKeepTheCapturedKeys(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (generated columns must be verified per engine)
    // Given: generated and explicit keys, one row deleted and one changed after the capture
    EngineScenario.Result run =
        applyAfterChange(
            "CREATE TABLE idb_types.typed (id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + " v TEXT)",
            "INSERT INTO idb_types.typed (v) VALUES ('a'), ('b');"
                + " INSERT INTO idb_types.typed VALUES (10, 'c')",
            "DELETE FROM idb_types.typed WHERE id = 1;"
                + " UPDATE idb_types.typed SET v = 'x' WHERE id = 2",
            refresh);
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(rows()).isEqualTo("(1,a) (2,b) (10,c)");
  }

  private EngineScenario.Result applyIdentityAlways(String refresh) throws Exception {
    return applyAfterChange(
        "CREATE TABLE idb_types.typed (id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
            + " v TEXT)",
        "INSERT INTO idb_types.typed (v) VALUES ('a'), ('b');"
            + " INSERT INTO idb_types.typed OVERRIDING SYSTEM VALUE VALUES (10, 'c')",
        "DELETE FROM idb_types.typed WHERE id = 1;"
            + " UPDATE idb_types.typed SET v = 'x' WHERE id = 2",
        refresh);
  }

  @Test
  void identityColumnsGeneratedAlwaysKeepTheCapturedKeys() throws Exception {
    // Spec: README.md > "Captured dataset" (identity GENERATED ALWAYS on PostgreSQL)
    // Given: generated and overridden keys, one row deleted and one changed after the capture
    // When
    EngineScenario.Result run = applyIdentityAlways("optimized");
    // Then: the upsert overrides the identity with the captured values
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(rows()).isEqualTo("(1,a) (2,b) (10,c)");
  }

  @Test
  void dbunitRefreshCannotWriteAnIdentityGeneratedAlwaysAndAppliesNothing() throws Exception {
    // Spec: README.md > "Captured dataset" (identity GENERATED ALWAYS needs the optimized path)
    // When: DBUnit's INSERT has no OVERRIDING SYSTEM VALUE
    EngineScenario.Result run = applyIdentityAlways("dbunit");
    // Then: the database's reason is shown and the table is left as it was
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("identity column defined as GENERATED ALWAYS");
    assertThat(rows()).isEqualTo("(2,x) (10,c)");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void storedGeneratedColumnsAreComputedAgain(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (generated columns must be verified per engine)
    // Given: a SELECT * capture, which includes the generated column
    EngineScenario.Result run =
        applyAfterChange(
            "CREATE TABLE idb_types.typed (id INT PRIMARY KEY, v INT,"
                + " doubled INT GENERATED ALWAYS AS (v * 2) STORED)",
            "INSERT INTO idb_types.typed (id, v) VALUES (1, 21), (2, 4)",
            "DELETE FROM idb_types.typed WHERE id = 1;"
                + " UPDATE idb_types.typed SET v = 5 WHERE id = 2",
            refresh);
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(rows()).isEqualTo("(1,21,42) (2,4,8)");
  }
}
