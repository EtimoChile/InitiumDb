package cl.etimo.initiumdb.db;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * SQL literals for hook variables (DEC-020), written the same way whatever the locale of the JVM or
 * the session: exact numbers in plain notation, text in single quotes, ANSI date and timestamp
 * literals and {@code TRUE}/{@code FALSE}. Engines add or remove cases on top of these.
 */
public final class SqlLiterals {

  private SqlLiterals() {}

  /**
   * The literal shared by the supported engines for a non-null value of the given JDBC type, or
   * empty when the type has no supported literal.
   */
  public static Optional<String> standard(Object value, int jdbcType) {
    return switch (jdbcType) {
      case Types.TINYINT,
          Types.SMALLINT,
          Types.INTEGER,
          Types.BIGINT,
          Types.NUMERIC,
          Types.DECIMAL ->
          number(value);
      case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR ->
          value instanceof String text ? Optional.of(text(text)) : Optional.empty();
      case Types.DATE ->
          value instanceof LocalDate date
              ? Optional.of("DATE '" + date(date) + "'")
              : Optional.empty();
      case Types.TIMESTAMP ->
          value instanceof LocalDateTime timestamp
              ? Optional.of("TIMESTAMP '" + timestamp(timestamp) + "'")
              : Optional.empty();
      case Types.BOOLEAN, Types.BIT ->
          value instanceof Boolean bool ? Optional.of(bool ? "TRUE" : "FALSE") : Optional.empty();
      default -> Optional.empty();
    };
  }

  /** {@code 'text'} with every single quote doubled; spaces, including padding, are kept. */
  public static String text(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /** {@code YYYY-MM-DD}. */
  public static String date(LocalDate date) {
    return String.format(
        java.util.Locale.ROOT,
        "%04d-%02d-%02d",
        date.getYear(),
        date.getMonthValue(),
        date.getDayOfMonth());
  }

  /** {@code YYYY-MM-DD HH:MI:SS}. */
  public static String dateTime(LocalDateTime timestamp) {
    LocalTime time = timestamp.toLocalTime();
    return date(timestamp.toLocalDate())
        + String.format(
            java.util.Locale.ROOT,
            " %02d:%02d:%02d",
            time.getHour(),
            time.getMinute(),
            time.getSecond());
  }

  /** {@code YYYY-MM-DD HH:MI:SS[.fffffffff]}, the fraction without trailing zeros. */
  public static String timestamp(LocalDateTime timestamp) {
    int nanos = timestamp.getNano();
    if (nanos == 0) {
      return dateTime(timestamp);
    }
    String fraction = String.format(java.util.Locale.ROOT, "%09d", nanos).replaceAll("0+$", "");
    return dateTime(timestamp) + "." + fraction;
  }

  private static Optional<String> number(Object value) {
    if (value instanceof BigDecimal decimal) {
      return Optional.of(decimal.toPlainString());
    }
    if (value instanceof Long
        || value instanceof Integer
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger) {
      return Optional.of(value.toString());
    }
    return Optional.empty();
  }
}
