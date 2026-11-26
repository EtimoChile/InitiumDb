package cl.etimo.initiumdb.db;

import java.sql.Connection;
import java.sql.SQLException;
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
}
