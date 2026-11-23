package cl.etimo.initiumdb.db.oracle;

import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.SqlIdentifiers;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.ext.oracle.Oracle10DataTypeFactory;

/** Oracle profile. */
public final class OracleEngine implements DatabaseEngine {

  @Override
  public String name() {
    return "oracle";
  }

  @Override
  public void configure(DatabaseConfig config) {
    config.setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new Oracle10DataTypeFactory());
    config.setProperty(DatabaseConfig.PROPERTY_ESCAPE_PATTERN, "\"?\"");
    config.setProperty(DatabaseConfig.FEATURE_CASE_SENSITIVE_TABLE_NAMES, true);
    config.setProperty(DatabaseConfig.FEATURE_SKIP_ORACLE_RECYCLEBIN_TABLES, true);
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
