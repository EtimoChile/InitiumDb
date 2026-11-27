package cl.etimo.initiumdb.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.datatype.AbstractDataType;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.TypeCastException;

/**
 * A timestamp without time zone (PostgreSQL {@code timestamp}, Oracle {@code DATE} and {@code
 * TIMESTAMP}) read and bound as {@link LocalDateTime}. DBUnit's own type goes through {@link
 * Timestamp}, which interprets the value in the JVM's time zone: a wall-clock time that does not
 * exist there (inside a daylight-saving gap) is silently moved, usually by one hour. Here the value
 * never meets a time zone.
 *
 * <p>The dataset text keeps the format DBUnit writes ({@code yyyy-MM-dd HH:mm:ss.f}, fraction
 * without trailing zeros, at least one digit), so existing datasets stay valid and unchanged.
 * Values that are not in that form (a date alone, an ISO {@code T}, a time zone suffix, epoch
 * milliseconds) are read as DBUnit reads them.
 */
public final class LocalTimestampDataType extends AbstractDataType {

  private static final DateTimeFormatter READ =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd")
          .optionalStart()
          .appendLiteral(' ')
          .optionalEnd()
          .optionalStart()
          .appendLiteral('T')
          .optionalEnd()
          .appendPattern("HH:mm")
          .optionalStart()
          .appendPattern(":ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
          .optionalEnd()
          .optionalEnd()
          .toFormatter(Locale.ROOT);

  private static final DateTimeFormatter WRITE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);

  private static final LocalTimestampDataType INSTANCE = new LocalTimestampDataType();

  private LocalTimestampDataType() {
    super("TIMESTAMP", Types.TIMESTAMP, String.class, false);
  }

  /**
   * Returns this type in place of DBUnit's {@code TIMESTAMP} for a column whose engine type has no
   * time zone; any other type is returned unchanged. A type name that mentions a time zone ({@code
   * timestamptz}, {@code TIMESTAMP WITH [LOCAL] TIME ZONE}) keeps DBUnit's type, which preserves
   * the instant.
   */
  public static DataType withoutTimeZone(DataType created, String sqlTypeName) {
    if (created != DataType.TIMESTAMP || sqlTypeName == null) {
      return created;
    }
    String name = sqlTypeName.toLowerCase(Locale.ROOT);
    boolean zoned = name.contains("tz") || name.contains("time zone");
    return zoned ? created : INSTANCE;
  }

  /** Formats a value the way DBUnit writes timestamps. */
  static String format(LocalDateTime value) {
    String fraction =
        String.format(Locale.ROOT, "%09d", value.getNano()).replaceFirst("(\\d)0+$", "$1");
    return WRITE.format(value) + "." + fraction;
  }

  /** The value as dataset text, or {@code null}. */
  @Override
  public Object typeCast(Object value) throws TypeCastException {
    LocalDateTime local = toLocal(value);
    return local == null ? null : format(local);
  }

  private static LocalDateTime toLocal(Object value) throws TypeCastException {
    if (value == null || value == ITable.NO_VALUE) {
      return null;
    }
    if (value instanceof LocalDateTime local) {
      return local;
    }
    if (value instanceof String text) {
      try {
        return LocalDateTime.parse(text.strip(), READ);
      } catch (DateTimeParseException e) {
        // other forms DBUnit accepts (zone suffix, date only, epoch millis)
      }
    }
    Object legacy = DataType.TIMESTAMP.typeCast(value);
    return legacy == null ? null : ((Timestamp) legacy).toLocalDateTime();
  }

  @Override
  public boolean isDateTime() {
    return true;
  }

  @Override
  public Object getSqlValue(int column, ResultSet resultSet) throws SQLException {
    LocalDateTime value = resultSet.getObject(column, LocalDateTime.class);
    return value == null ? null : format(value);
  }

  @Override
  public void setSqlValue(Object value, int column, PreparedStatement statement)
      throws SQLException, TypeCastException {
    LocalDateTime local = toLocal(value);
    if (local == null) {
      statement.setNull(column, Types.TIMESTAMP);
    } else {
      statement.setObject(column, local);
    }
  }
}
