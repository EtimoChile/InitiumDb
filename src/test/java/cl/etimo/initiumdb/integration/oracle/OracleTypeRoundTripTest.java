package cl.etimo.initiumdb.integration.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.EngineScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
    createTable(type);
    execute("INSERT INTO IDB_TYPES.TYPED VALUES (1, " + literal + ")");
    captureDeleteAndPrepare(refresh);
    return scalar("SELECT " + text.formatted("V") + " FROM IDB_TYPES.TYPED WHERE ID = 1");
  }

  private static void createTable(String type) throws SQLException {
    execute(
        "DROP TABLE IF EXISTS IDB_TYPES.TYPED",
        "CREATE TABLE IDB_TYPES.TYPED (ID NUMBER PRIMARY KEY, V " + type + ")");
  }

  private EngineScenario scenario(String queries) throws Exception {
    return new EngineScenario(
        root, "oracle", ORACLE.getJdbcUrl(), "system", ORACLE.getPassword(), queries);
  }

  private void captureDeleteAndPrepare(String refresh) throws Exception {
    EngineScenario scenario = scenario(QUERIES);
    EngineScenario.Result captured = scenario.capture();
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    execute("DELETE FROM IDB_TYPES.TYPED");
    EngineScenario.Result prepared = scenario.prepare(refresh);
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
  }

  /**
   * As {@link #roundTrip}, with the value bound from Java (LOBs larger than a SQL literal allows),
   * and read back with {@code getBytes} or {@code getString}.
   */
  private Object boundRoundTrip(String type, Object value, String refresh) throws Exception {
    createTable(type);
    try (Connection c = connection();
        PreparedStatement s = c.prepareStatement("INSERT INTO IDB_TYPES.TYPED VALUES (1, ?)")) {
      if (value instanceof byte[] bytes) {
        s.setBytes(1, bytes);
      } else {
        s.setString(1, (String) value);
      }
      s.executeUpdate();
    }
    captureDeleteAndPrepare(refresh);
    try (Connection c = connection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT V FROM IDB_TYPES.TYPED WHERE ID = 1")) {
      assertThat(rs.next()).isTrue();
      return value instanceof byte[] ? rs.getBytes(1) : rs.getString(1);
    }
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

  // ---- control characters ----

  @Test
  void textWithAControlCharacterFailsTheCaptureAndKeepsThePreviousDataset() throws Exception {
    // Spec: README.md > "Captured dataset" (XML 1.0 cannot hold most control characters)
    // Given: a previous dataset, and a row whose text holds U+0001
    createTable("VARCHAR2(50)");
    execute(
        "INSERT INTO IDB_TYPES.TYPED VALUES (1, 'ok')",
        "INSERT INTO IDB_TYPES.TYPED VALUES (2, 'secret' || CHR(1) || 'value')");
    EngineScenario scenario =
        scenario(
            QUERIES.replace(
                "name: TYPED\n", "name: TYPED\n    sql: SELECT * FROM TYPED ORDER BY ID\n"));
    Files.writeString(scenario.dataset(), "<dataset/>");

    // When
    EngineScenario.Result captured = scenario.capture();

    // Then: the cell is named, the value is not shown, and the dataset is untouched
    assertThat(captured.exitCode()).isEqualTo(1);
    assertThat(captured.err())
        .contains("Table 'TYPED', column 'V', row 2 holds character U+0001")
        .doesNotContain("secret");
    assertThat(scenario.readDataset()).isEqualTo("<dataset/>");
  }

  // ---- binary and large values ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void rawKeepsItsBytes(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (binary data must be verified per engine)
    assertRoundTrip("RAW(16)", "HEXTORAW('00FF7F8001')", "RAWTOHEX(%s)", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void blobKeepsEveryByteValue(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (binary data must be verified per engine)
    byte[] every = new byte[256];
    for (int i = 0; i < every.length; i++) {
      every[i] = (byte) i;
    }
    assertThat((byte[]) boundRoundTrip("BLOB", every, refresh)).isEqualTo(every);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aLargeBlobRoundTrips(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (binary data must be verified per engine)
    byte[] large = new byte[2_000_000];
    for (int i = 0; i < large.length; i++) {
      large[i] = (byte) (i * 31 + i / 7);
    }
    assertThat((byte[]) boundRoundTrip("BLOB", large, refresh)).isEqualTo(large);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void anEmptyBlobIsAppliedAsNullAsOracleDoesWithEveryEmptyValue(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (on Oracle an empty BLOB is applied as NULL)
    // Given: an empty, not NULL, BLOB, captured as an empty attribute
    // When: deleted and prepared again
    String actual =
        roundTrip("BLOB", "EMPTY_BLOB()", "NVL(TO_CHAR(DBMS_LOB.GETLENGTH(%s)), 'NULL')", refresh);
    // Then: Oracle stores the zero-length value it is sent as NULL, as it does for ''
    assertThat(Files.readString(root.resolve("project").resolve("CRM.dataset.xml")))
        .contains("<TYPED ID=\"1\" V=\"\"/>");
    assertThat(actual).isEqualTo("NULL");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aLargeClobRoundTrips(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    String large = "línea ñ <&> €\r\n\t".repeat(100_000);
    assertThat(boundRoundTrip("CLOB", large, refresh)).isEqualTo(large);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aLargeNclobRoundTrips(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    String large = "Ωmega ñandú 😀 \"quoted\"\n".repeat(50_000);
    assertThat(boundRoundTrip("NCLOB", large, refresh)).isEqualTo(large);
  }

  // ---- numbers ----

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void numberKeepsEveryDigit(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip(
        "NUMBER(38,10)", "-1234567890123456789012345678.0123456789", "TO_CHAR(%s)", refresh);
    assertRoundTrip("NUMBER(12,4)", "5.1", "TO_CHAR(%s)", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void numberWithoutPrecisionKeepsVeryLargeAndVerySmallValues(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    // TO_CHAR rounds to 40 digits, so the comparison also checks the value itself
    String exact = "TO_CHAR(%1$s) || '|' || CASE WHEN %1$s = %2$s THEN 'same' END";
    for (String value :
        new String[] {
          "12345678901234567890123456789012345678",
          "-0.00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001",
          "9.99999999999999999999999999999999999999E125",
          "123.456"
        }) {
      assertRoundTrip("NUMBER", value, exact.replace("%2$s", value), refresh);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void integersKeepTheirLimits(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("NUMBER(19)", "-9223372036854775808", "TO_CHAR(%s)", refresh);
    assertRoundTrip("NUMBER(38)", "99999999999999999999999999999999999999", "TO_CHAR(%s)", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void binaryFloatingPointValuesKeepEveryBit(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    // A hex dump of the stored bytes shows any change in the last bit
    String bits = "RAWTOHEX(UTL_RAW.CAST_FROM_BINARY_DOUBLE(%s))";
    assertRoundTrip("BINARY_DOUBLE", "0.1D", bits, refresh);
    assertRoundTrip("BINARY_DOUBLE", "1.7976931348623157E308D", bits, refresh);
    assertRoundTrip("BINARY_DOUBLE", "4.9E-324D", bits, refresh);
    assertRoundTrip("BINARY_DOUBLE", "-0D", bits, refresh);
    assertRoundTrip(
        "BINARY_FLOAT", "0.1F", "RAWTOHEX(UTL_RAW.CAST_FROM_BINARY_FLOAT(%s))", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void binaryFloatingPointSpecialValuesRoundTrip(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("BINARY_DOUBLE", "BINARY_DOUBLE_NAN", "TO_CHAR(%s)", refresh);
    assertRoundTrip("BINARY_DOUBLE", "-BINARY_DOUBLE_INFINITY", "TO_CHAR(%s)", refresh);
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void floatKeepsItsDecimalDigits(String refresh) throws Exception {
    // Spec: README.md > "Captured dataset" (types must be verified per engine)
    assertRoundTrip("FLOAT", "0.1", "TO_CHAR(%s)", refresh);
    assertRoundTrip(
        "FLOAT(126)", "1.23456789012345678901234567890123456789", "TO_CHAR(%s)", refresh);
  }
}
