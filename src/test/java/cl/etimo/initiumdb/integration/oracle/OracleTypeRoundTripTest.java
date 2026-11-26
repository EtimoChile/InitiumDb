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
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.oracle.OracleContainer;

/**
 * Contract: README.md > "Captured dataset" (handling of nulls and types must be verified per
 * engine), against a disposable Oracle. A value is captured, its row deleted, and the dataset
 * prepared again; the value read back must equal the original exactly. Every case runs on both
 * apply paths: the batched {@code MERGE} ({@code --refresh optimized}) and DBUnit's {@code REFRESH}
 * ({@code --refresh dbunit}), which tables with LOB or engine-specific columns take.
 */
@Tag("oracle_integration")
class OracleTypeRoundTripTest {

  private static final OracleContainer ORACLE =
      new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
          // The image takes about two minutes to open its database on a laptop.
          .withStartupTimeout(Duration.ofMinutes(6));

  private static final String QUERIES = "schema: IDB_TYPES\ntables:\n  - name: TYPED\n";

  private static final String DATE_TEXT = "TO_CHAR(%s, 'YYYY-MM-DD HH24:MI:SS')";
  private static final String TIMESTAMP_TEXT = "TO_CHAR(%s, 'YYYY-MM-DD HH24:MI:SS.FF9')";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    ORACLE.start();
    execute("CREATE USER IDB_TYPES IDENTIFIED BY \"Types#12345\" QUOTA UNLIMITED ON USERS");
  }

  @AfterAll
  static void stopDatabase() {
    ORACLE.stop();
  }

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

  /**
   * Captures one row holding {@code literal} in a column of {@code type}, deletes the row, prepares
   * the dataset with {@code refresh} and returns the column rendered with {@code text} (a format
   * with one {@code %s} for the column).
   */
  private String roundTrip(String type, String literal, String text, String refresh)
      throws Exception {
    execute(
        "DROP TABLE IF EXISTS IDB_TYPES.TYPED",
        "CREATE TABLE IDB_TYPES.TYPED (ID NUMBER PRIMARY KEY, V " + type + ")",
        "INSERT INTO IDB_TYPES.TYPED VALUES (1, " + literal + ")");
    EngineScenario scenario =
        new EngineScenario(
            root, "oracle", ORACLE.getJdbcUrl(), "system", ORACLE.getPassword(), QUERIES);
    EngineScenario.Result captured = scenario.capture();
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    execute("DELETE FROM IDB_TYPES.TYPED");
    EngineScenario.Result prepared = scenario.prepare(refresh);
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    return scalar("SELECT " + text.formatted("V") + " FROM IDB_TYPES.TYPED WHERE ID = 1");
  }

  private void assertRoundTrip(String type, String literal, String text, String refresh)
      throws Exception {
    // Given: one row holding the literal
    // When: captured, deleted and prepared again
    String actual = roundTrip(type, literal, text, refresh);
    // Then: the stored value is unchanged
    String expected =
        scalar("SELECT " + text.formatted("CAST(" + literal + " AS " + type + ")") + " FROM dual");
    assertThat(actual).as(type + " " + literal).isEqualTo(expected);
  }

  // ---- nulls and text ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aCapturedNullStaysNull(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (Flat XML omits NULLs; declared columns apply as NULL)
    assertThat(roundTrip("VARCHAR2(20)", "NULL", "%s", refresh)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void textWithNewlinesTabsAndOuterSpacesIsKeptExactly(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    // XML attribute normalization would turn unescaped line breaks and tabs into spaces
    assertRoundTrip(
        "VARCHAR2(100)",
        "'  line 1' || CHR(13) || CHR(10) || 'line 2' || CHR(10) || CHR(9) || 'end  '",
        "%s",
        refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void nationalTextWithXmlMarkupAndNonAsciiCharactersIsKeptExactly(String refresh)
      throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("NVARCHAR2(100)", "N'<a href=\"x\">&amp; ''ñandú'' €</a>'", "%s", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void charColumnsKeepTheirPadding(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("CHAR(5)", "'ab'", "'[' || %s || ']'", refresh);
  }

  // ---- dates and times ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aDateKeepsItsTimeOfDay(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("DATE", "TIMESTAMP '2024-03-15 13:45:07'", DATE_TEXT, refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void timestampsKeepEveryFractionalDigit(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip(
        "TIMESTAMP(9)", "TIMESTAMP '2024-03-15 13:45:07.123456789'", TIMESTAMP_TEXT, refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void timestampsWithTimeZoneKeepTheirInstant(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip(
        "TIMESTAMP(6) WITH TIME ZONE",
        "TIMESTAMP '2024-03-15 13:45:07.5 +05:30'",
        "TO_CHAR(SYS_EXTRACT_UTC(%s), 'YYYY-MM-DD HH24:MI:SS.FF6')",
        refresh);
  }

  /**
   * In America/Santiago clocks jumped from 2024-09-08 00:00 to 01:00, so 00:30 does not exist in
   * that zone. A {@code DATE} or {@code TIMESTAMP} has no zone and must not be shifted by the zone
   * of the machine that runs InitiumDb.
   */
  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aDateOrTimestampInsideTheLocalDaylightSavingGapIsNotShifted(String refresh)
      throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    TimeZone previous = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"));
    try {
      assertRoundTrip("DATE", "TIMESTAMP '2024-09-08 00:30:00'", DATE_TEXT, refresh);
      assertRoundTrip("TIMESTAMP(6)", "TIMESTAMP '2024-09-08 00:30:00'", TIMESTAMP_TEXT, refresh);
    } finally {
      TimeZone.setDefault(previous);
    }
  }
}
