package cl.etimo.initiumdb.core;

import java.sql.Connection;
import java.sql.SQLException;

/** Separates connection resolution from data operations. */
public interface ConnectionResolver {

  /** Opens a connection to the physical database behind {@code databaseAlias}. */
  Connection resolve(String environment, String databaseAlias) throws SQLException;
}
