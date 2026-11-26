package cl.etimo.initiumdb.db.postgresql;

import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.FloatingPointDataType;
import cl.etimo.initiumdb.db.LocalTimestampDataType;
import cl.etimo.initiumdb.db.SqlIdentifiers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Locale;
import java.util.Set;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.DataTypeException;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.dbunit.operation.DatabaseOperation;

/** PostgreSQL profile. */
public final class PostgresqlEngine implements DatabaseEngine {

  @Override
  public String name() {
    return "postgresql";
  }

  @Override
  public void configure(DatabaseConfig config) {
    config.setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new DataTypes());
    config.setProperty(DatabaseConfig.PROPERTY_ESCAPE_PATTERN, "\"?\"");
    config.setProperty(DatabaseConfig.FEATURE_CASE_SENSITIVE_TABLE_NAMES, true);
  }

  @Override
  public DatabaseOperation refreshOperation() {
    return new PostgresqlUpsertOperation();
  }

  @Override
  public void useSchema(Connection connection, String schema) throws SQLException {
    if (schema == null || schema.isBlank()) {
      return;
    }
    // PostgreSQL accepts a search_path naming a schema that does not exist, and the failure only
    // shows later as "relation does not exist"; check it here to fail with the cause.
    if (!schemaExists(connection, schema)) {
      throw new InitiumDbException(
          "Schema '"
              + schema
              + "' does not exist in the database (schema names are case-sensitive).");
    }
    String identifier = SqlIdentifiers.quote(schema);
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + identifier);
    }
  }

  /**
   * DBUnit's PostgreSQL types, with {@code timestamp} kept free of the JVM's time zone, floating
   * point and {@code numeric} accepting {@code NaN} and the infinities, and the types DBUnit does
   * not know (arrays, enums, {@code json}, {@code interval}, ...) kept as PostgreSQL's own text.
   */
  private static final class DataTypes extends PostgresqlDataTypeFactory {

    /** The names pgjdbc reports for character columns; any other {@code VARCHAR} is an enum. */
    private static final Set<String> TEXT_TYPES =
        Set.of("varchar", "text", "bpchar", "char", "name");

    @Override
    public DataType createDataType(int sqlType, String sqlTypeName) throws DataTypeException {
      DataType created = super.createDataType(sqlType, sqlTypeName);
      if (created == DataType.NUMERIC || created == DataType.DECIMAL) {
        return NumericDataType.INSTANCE;
      }
      if (created == DataType.UNKNOWN || sqlType == Types.ARRAY || isEnum(sqlType, sqlTypeName)) {
        return TextCastDataType.INSTANCE;
      }
      return FloatingPointDataType.replacing(
          LocalTimestampDataType.withoutTimeZone(created, sqlTypeName));
    }

    private static boolean isEnum(int sqlType, String sqlTypeName) {
      return sqlType == Types.VARCHAR
          && sqlTypeName != null
          && !TEXT_TYPES.contains(sqlTypeName.toLowerCase(Locale.ROOT));
    }
  }

  private static boolean schemaExists(Connection connection, String schema) throws SQLException {
    try (ResultSet schemas = connection.getMetaData().getSchemas()) {
      while (schemas.next()) {
        if (schema.equals(schemas.getString("TABLE_SCHEM"))) {
          return true;
        }
      }
    }
    return false;
  }
}
