package cl.etimo.initiumdb.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Contract: README.md > "Hook variables" (the literal written by {@code ${name}} for each supported
 * type, per engine). Driven through the public engine contract with the values and JDBC metadata
 * each driver reports; the integration tests run the literals against the real engines.
 */
class HookLiteralContractTest {

  private final DatabaseEngine oracle = Engines.named("oracle");
  private final DatabaseEngine postgresql = Engines.named("postgresql");

  private static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 27, 13, 45, 7, 120_000_000);

  @Test
  void exactNumbersAreWrittenInPlainNotationOnBothEngines() {
    // Spec: README.md > Hook variables (exact number: plain notation, no exponent, no grouping)
    for (DatabaseEngine engine : new DatabaseEngine[] {oracle, postgresql}) {
      assertThat(engine.literal(new BigDecimal("1E+3"), Types.NUMERIC, "NUMBER")).hasValue("1000");
      assertThat(engine.literal(new BigDecimal("-0.000012500"), Types.NUMERIC, "numeric"))
          .hasValue("-0.000012500");
      assertThat(engine.literal(9_223_372_036_854_775_807L, Types.BIGINT, "int8"))
          .hasValue("9223372036854775807");
      assertThat(engine.literal(42, Types.INTEGER, "int4")).hasValue("42");
    }
    // Oracle FLOAT is a decimal NUMBER, exact even when reported as a floating type
    assertThat(oracle.literal(new BigDecimal("0.000125"), Types.FLOAT, "FLOAT"))
        .hasValue("0.000125");
  }

  @Test
  void literalsDoNotDependOnTheJvmLocale() {
    // Spec: README.md > Hook variables (never from the locale or NLS settings)
    Locale before = Locale.getDefault();
    try {
      Locale.setDefault(Locale.of("ar", "EG"));
      assertThat(postgresql.literal(new BigDecimal("1234.5"), Types.NUMERIC, "numeric"))
          .hasValue("1234.5");
      assertThat(postgresql.literal(LocalDate.of(2026, 1, 2), Types.DATE, "date"))
          .hasValue("DATE '2026-01-02'");
      assertThat(oracle.literal(NOON, Types.TIMESTAMP, "DATE"))
          .hasValue("TO_DATE('2026-11-27 13:45:07', 'YYYY-MM-DD HH24:MI:SS')");
    } finally {
      Locale.setDefault(before);
    }
  }

  @Test
  void textIsQuotedWithEveryQuoteDoubledAndPaddingKept() {
    // Spec: README.md > Hook variables (text: single quotes, each ' doubled)
    assertThat(oracle.literal("O'Brien ", Types.CHAR, "CHAR")).hasValue("'O''Brien '");
    assertThat(oracle.literal("ñandú", Types.NVARCHAR, "NVARCHAR2")).hasValue("'ñandú'");
    assertThat(postgresql.literal("a''b", Types.VARCHAR, "text")).hasValue("'a''''b'");
    // PostgreSQL enum values are reported as VARCHAR with the enum's name
    assertThat(postgresql.literal("ACTIVE", Types.VARCHAR, "status")).hasValue("'ACTIVE'");
  }

  @Test
  void oracleDatesKeepTheTimeOfDay() {
    // Spec: README.md > Hook variables (Oracle DATE: TO_DATE with an explicit format)
    assertThat(oracle.literal(NOON, Types.TIMESTAMP, "DATE"))
        .hasValue("TO_DATE('2026-11-27 13:45:07', 'YYYY-MM-DD HH24:MI:SS')");
    assertThat(oracle.literal(NOON, Types.TIMESTAMP, "TIMESTAMP"))
        .hasValue("TIMESTAMP '2026-11-27 13:45:07.12'");
    assertThat(oracle.literal(NOON.withNano(0), Types.TIMESTAMP, "TIMESTAMP(6)"))
        .hasValue("TIMESTAMP '2026-11-27 13:45:07'");
  }

  @Test
  void postgresqlDatesTimestampsAndBooleansUseAnsiLiterals() {
    // Spec: README.md > Hook variables (date, timestamp, boolean on PostgreSQL)
    assertThat(postgresql.literal(LocalDate.of(2026, 11, 27), Types.DATE, "date"))
        .hasValue("DATE '2026-11-27'");
    assertThat(postgresql.literal(NOON.withNano(1_000), Types.TIMESTAMP, "timestamp"))
        .hasValue("TIMESTAMP '2026-11-27 13:45:07.000001'");
    assertThat(postgresql.literal(true, Types.BIT, "bool")).hasValue("TRUE");
    assertThat(postgresql.literal(false, Types.BOOLEAN, "bool")).hasValue("FALSE");
  }

  @Test
  void typesOutsideTheSupportedSetHaveNoLiteral() {
    // Spec: README.md > Hook variables (any other type fails when written with ${name})
    assertThat(oracle.literal(true, Types.BOOLEAN, "BOOLEAN")).isEmpty();
    assertThat(oracle.literal(1.5d, Types.DOUBLE, "BINARY_DOUBLE")).isEmpty();
    assertThat(oracle.literal(new byte[] {1}, Types.VARBINARY, "RAW")).isEmpty();
    assertThat(postgresql.literal(true, Types.BIT, "bit")).isEmpty();
    assertThat(
            postgresql.literal(
                OffsetDateTime.of(NOON, ZoneOffset.UTC), Types.TIMESTAMP, "timestamptz"))
        .isEmpty();
    assertThat(postgresql.literal(0.25f, Types.REAL, "float4")).isEmpty();
    assertThat(postgresql.literal("{}", Types.OTHER, "jsonb")).isEmpty();
    assertThat(postgresql.literal("12:00", Types.TIME, "time")).isEmpty();
  }
}
