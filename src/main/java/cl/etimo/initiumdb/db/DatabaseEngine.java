package cl.etimo.initiumdb.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.operation.DatabaseOperation;

/**
 * Engine contract: the minimal engine-specific behavior InitiumDb needs on top of DBUnit. A thin
 * profile (DEC-004), not an adapter hierarchy.
 */
public interface DatabaseEngine {

  /** Engine name as written in environment files ({@code oracle}, {@code postgresql}). */
  String name();

  /** Applies the DBUnit settings this engine needs (data type factory and engine features). */
  void configure(DatabaseConfig config);

  /**
   * Makes {@code schema} the default schema of the session so unqualified table names resolve to
   * it. The schema is used exactly as written (same case as the data dictionary) and quoted. A
   * {@code null} or blank schema leaves the session unchanged.
   */
  void useSchema(Connection connection, String schema) throws SQLException;

  /**
   * The operation that applies a dataset by updating matching rows and inserting missing ones.
   * Defaults to DBUnit's {@code REFRESH} ({@link DbUnitRefreshOperation}); an engine may replace it
   * with a faster one that gives the same result.
   */
  default DatabaseOperation refreshOperation() {
    return new DbUnitRefreshOperation();
  }

  /**
   * Reads column {@code column} of the current row for a hook variable (DEC-020). Dates and
   * timestamps without time zone are read as {@link java.time.LocalDate} and {@link
   * java.time.LocalDateTime}, free of the JVM's time zone (DEC-015); anything else as the driver's
   * own object.
   */
  default Object hookValue(ResultSet rows, int column) throws SQLException {
    return switch (rows.getMetaData().getColumnType(column)) {
      case Types.DATE -> rows.getObject(column, LocalDate.class);
      case Types.TIMESTAMP -> rows.getObject(column, LocalDateTime.class);
      default -> rows.getObject(column);
    };
  }

  /**
   * Writes a non-null hook variable as an SQL literal of this engine (DEC-020), or empty when its
   * type has no supported literal. {@code jdbcType} and {@code typeName} come from the variable's
   * column metadata. Defaults to {@link SqlLiterals#standard}.
   */
  default Optional<String> literal(Object value, int jdbcType, String typeName) {
    return SqlLiterals.standard(value, jdbcType);
  }

  /**
   * Binds a hook variable (DEC-020), {@code null} included, so the database receives it with the
   * type of the column it was read from. {@code jdbcType} and {@code typeName} come from that
   * column's metadata. Defaults to {@code setNull} with the JDBC type, or {@code setObject}.
   */
  default void bindHookValue(
      PreparedStatement statement, int index, Object value, int jdbcType, String typeName)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, jdbcType);
    } else {
      statement.setObject(index, value);
    }
  }
}
