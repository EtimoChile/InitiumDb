package cl.etimo.initiumdb.db.postgresql;

import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.SqlIdentifiers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.dbunit.database.DatabaseConfig;
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
    config.setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory());
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
