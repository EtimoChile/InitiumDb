package cl.etimo.initiumdb.integration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.EngineScenario;
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
    return scalar("SELECT v::text FROM idb_types.typed WHERE id = 1");
  }

  private static String expected(String type, String literal) throws SQLException {
    return scalar("SELECT CAST(" + literal + " AS " + type + ")::text");
  }

  private void assertRoundTrip(String type, String literal, String refresh) throws Exception {
    // Given: one row holding the literal
    // When: captured, deleted and prepared again
    String actual = roundTrip(type, literal, refresh);
    // Then: the stored value is unchanged
    assertThat(actual).as(type + " " + literal).isEqualTo(expected(type, literal));
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
}
