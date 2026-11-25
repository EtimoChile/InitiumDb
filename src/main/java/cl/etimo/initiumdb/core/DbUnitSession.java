package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.db.DatabaseEngine;
import java.sql.Connection;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.database.IDatabaseConnection;

/** Opens a DBUnit connection configured for an engine. */
final class DbUnitSession {

  private DbUnitSession() {}

  /** The returned connection is not closed by callers: the JDBC connection stays theirs. */
  static IDatabaseConnection open(Connection connection, DatabaseEngine engine, String schema)
      throws DatabaseUnitException {
    IDatabaseConnection dbunit =
        schema == null || schema.isBlank()
            ? new DatabaseConnection(connection)
            : new ExactSchemaConnection(connection, schema);
    engine.configure(dbunit.getConfig());
    return dbunit;
  }

  /**
   * DBUnit's constructor folds the schema to the engine's case ({@code SQLHelper.correctCase}),
   * which breaks schemas created with quoted mixed-case names. The schema is a name from the data
   * dictionary and must be used exactly as written, so it is returned unchanged here.
   */
  private static final class ExactSchemaConnection extends DatabaseConnection {

    private final String exactSchema;

    ExactSchemaConnection(Connection connection, String schema) throws DatabaseUnitException {
      super(connection, schema);
      this.exactSchema = schema;
    }

    @Override
    public String getSchema() {
      return exactSchema;
    }
  }
}
