package cl.etimo.initiumdb.db.postgresql;

import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.SqlIdentifiers;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;

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
  public void useSchema(Connection connection, String schema) throws SQLException {
    if (schema == null || schema.isBlank()) {
      return;
    }
    String identifier = SqlIdentifiers.quote(schema);
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + identifier);
    }
  }
}
