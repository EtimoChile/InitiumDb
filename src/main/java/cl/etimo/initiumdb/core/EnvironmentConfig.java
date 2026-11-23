package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.db.Engines;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Environment configuration: maps case aliases to physical databases. Holds no passwords. */
public record EnvironmentConfig(String environment, Map<String, DatabaseSettings> databases) {

  /** Connection settings of one alias. {@code credential} names a secret, it is not a password. */
  public record DatabaseSettings(
      String engine, String jdbcUrl, String username, String credential) {

    /** Omits the JDBC URL so it never reaches logs. */
    @Override
    public String toString() {
      return "DatabaseSettings[engine=" + engine + ", credential=" + credential + "]";
    }
  }

  /** Returns the settings of an alias or fails naming the alias and environment. */
  public DatabaseSettings database(String alias) {
    DatabaseSettings settings = databases.get(alias);
    if (settings == null) {
      throw new InitiumDbException(
          "Alias '" + alias + "' is not defined in environment '" + environment + "'.");
    }
    return settings;
  }

  /** Loads {@code file}, which must declare {@code expectedEnvironment}. */
  public static EnvironmentConfig load(Path file, String expectedEnvironment) {
    ObjectNode root = YamlFiles.readObject(file, "Environment file");
    YamlFiles.requireVersion(root, file);
    String environment = YamlFiles.requiredText(root, "environment", file);
    if (!environment.equals(expectedEnvironment)) {
      throw new InitiumDbException(
          "Environment file "
              + file
              + " declares environment '"
              + environment
              + "', expected '"
              + expectedEnvironment
              + "'.");
    }
    ObjectNode databases = YamlFiles.requiredObject(root, "databases", file);
    Map<String, DatabaseSettings> result = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : databases.properties()) {
      JsonNode node = entry.getValue();
      if (!(node instanceof ObjectNode)) {
        throw new InitiumDbException(
            "Database '" + entry.getKey() + "' in " + file + " must be a mapping.");
      }
      String engine = YamlFiles.requiredText(node, "engine", file);
      Engines.named(engine);
      result.put(
          entry.getKey(),
          new DatabaseSettings(
              engine,
              YamlFiles.requiredText(node, "jdbcUrl", file),
              YamlFiles.requiredText(node, "username", file),
              YamlFiles.requiredText(node, "credential", file)));
    }
    return new EnvironmentConfig(environment, Map.copyOf(result));
  }
}
