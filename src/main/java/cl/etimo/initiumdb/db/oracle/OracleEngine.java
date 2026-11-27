package cl.etimo.initiumdb.db.oracle;

import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.FloatingPointDataType;
import cl.etimo.initiumdb.db.LocalTimestampDataType;
import cl.etimo.initiumdb.db.SqlIdentifiers;
import cl.etimo.initiumdb.db.SqlLiterals;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.DataTypeException;
import org.dbunit.ext.oracle.Oracle10DataTypeFactory;
import org.dbunit.operation.DatabaseOperation;

/** Oracle profile. */
public final class OracleEngine implements DatabaseEngine {

  @Override
  public String name() {
    return "oracle";
  }

  @Override
  public void configure(DatabaseConfig config) {
    config.setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new DataTypes());
    config.setProperty(DatabaseConfig.PROPERTY_ESCAPE_PATTERN, "\"?\"");
    config.setProperty(DatabaseConfig.FEATURE_CASE_SENSITIVE_TABLE_NAMES, true);
    config.setProperty(DatabaseConfig.FEATURE_SKIP_ORACLE_RECYCLEBIN_TABLES, true);
  }

  @Override
  public DatabaseOperation refreshOperation() {
    return new OracleMergeOperation();
  }

  /**
   * DBUnit's Oracle types, with {@code DATE} and {@code TIMESTAMP} free of the JVM's time zone,
   * {@code FLOAT} (a decimal type of up to 38 digits) kept exact, and {@code BINARY_DOUBLE} and
   * {@code BINARY_FLOAT} bound as themselves rather than as {@code NUMBER}.
   */
  private static final class DataTypes extends Oracle10DataTypeFactory {
    @Override
    public DataType createDataType(int sqlType, String sqlTypeName) throws DataTypeException {
      String name = sqlTypeName == null ? "" : sqlTypeName.toUpperCase(Locale.ROOT);
      return switch (name) {
        case "FLOAT" -> DataType.NUMERIC;
        case "BINARY_DOUBLE" ->
            FloatingPointDataType.boundAs(false, FloatingPointDataType.ORACLE_BINARY_DOUBLE);
        case "BINARY_FLOAT" ->
            FloatingPointDataType.boundAs(true, FloatingPointDataType.ORACLE_BINARY_FLOAT);
        default ->
            LocalTimestampDataType.withoutTimeZone(
                super.createDataType(sqlType, sqlTypeName), sqlTypeName);
      };
    }
  }

  /** {@code DATE} holds a time of day, so it is read as a {@link LocalDateTime} (DEC-020). */
  @Override
  public Object hookValue(ResultSet rows, int column) throws SQLException {
    if (isDate(rows.getMetaData().getColumnTypeName(column))) {
      return rows.getObject(column, LocalDateTime.class);
    }
    return DatabaseEngine.super.hookValue(rows, column);
  }

  /**
   * The standard literals, except {@code DATE}, written with {@code TO_DATE} and an explicit format
   * because the ANSI {@code DATE '...'} literal drops the time of day, {@code FLOAT}, an exact
   * number whatever JDBC type the driver reports, and {@code BOOLEAN}, which is not supported.
   */
  @Override
  public Optional<String> literal(Object value, int jdbcType, String typeName) {
    if (isDate(typeName)) {
      return value instanceof LocalDateTime date
          ? Optional.of("TO_DATE('" + SqlLiterals.dateTime(date) + "', 'YYYY-MM-DD HH24:MI:SS')")
          : Optional.empty();
    }
    if (jdbcType == Types.BOOLEAN || jdbcType == Types.BIT) {
      return Optional.empty();
    }
    // FLOAT is a decimal NUMBER of up to 38 digits, read as a BigDecimal whatever type is reported
    if (typeName != null
        && typeName.equalsIgnoreCase("FLOAT")
        && value instanceof java.math.BigDecimal decimal) {
      return Optional.of(decimal.toPlainString());
    }
    return SqlLiterals.standard(value, jdbcType);
  }

  private static boolean isDate(String typeName) {
    return typeName != null && typeName.equalsIgnoreCase("DATE");
  }

  @Override
  public void useSchema(Connection connection, String schema) throws SQLException {
    if (schema == null || schema.isBlank()) {
      return;
    }
    String identifier = SqlIdentifiers.quote(schema);
    try (Statement statement = connection.createStatement()) {
      statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + identifier);
    }
  }
}
