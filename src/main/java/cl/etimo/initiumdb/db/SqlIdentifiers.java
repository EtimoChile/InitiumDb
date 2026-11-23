package cl.etimo.initiumdb.db;

/**
 * Identifiers that name database objects (schema, tables) are written exactly as in the data
 * dictionary. They are never case-folded; when spliced into a statement they are double-quoted so
 * the engine takes them literally.
 */
public final class SqlIdentifiers {

  private SqlIdentifiers() {}

  /** Returns {@code name} as a double-quoted identifier that preserves it exactly. */
  public static String quote(String name) {
    return '"' + name.replace("\"", "\"\"") + '"';
  }
}
