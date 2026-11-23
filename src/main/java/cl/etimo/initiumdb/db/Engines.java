package cl.etimo.initiumdb.db;

import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.db.oracle.OracleEngine;
import cl.etimo.initiumdb.db.postgresql.PostgresqlEngine;
import java.util.List;

/** Registry of supported engines. */
public final class Engines {

  private static final List<DatabaseEngine> ALL =
      List.of(new OracleEngine(), new PostgresqlEngine());

  private Engines() {}

  /** Returns the engine with the given name or fails listing the supported ones. */
  public static DatabaseEngine named(String name) {
    for (DatabaseEngine engine : ALL) {
      if (engine.name().equals(name)) {
        return engine;
      }
    }
    throw new InitiumDbException(
        "Unsupported engine '"
            + name
            + "'. Supported: "
            + ALL.stream().map(DatabaseEngine::name).toList()
            + ".");
  }
}
