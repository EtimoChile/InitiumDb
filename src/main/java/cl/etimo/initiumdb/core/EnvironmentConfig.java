package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.db.Engines;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Environment configuration: maps case aliases to physical databases. Holds no passwords. Aliases
 * follow {@link Names}. Each field can also come from {@code IDB_<ALIAS>_ENGINE}, {@code
 * IDB_<ALIAS>_URL} and {@code IDB_<ALIAS>_USER} environment variables, which win over the file.
 */
public record EnvironmentConfig(String environment, Map<String, DatabaseSettings> databases) {

  /**
   * Connection settings of one alias. {@code credential} names a secret (default: the alias), it is
   * not a password.
   */
  public record DatabaseSettings(
      String engine, String jdbcUrl, String username, String credential) {

    /** Omits the JDBC URL so it never reaches logs. */
    @Override
    public String toString() {
      return "DatabaseSettings[engine=" + engine + ", credential=" + credential + "]";
    }
  }

  private static final String[] FIELDS = {"ENGINE", "URL", "USER"};

  /**
   * Name of the environment variable holding a field ({@code ENGINE}, {@code URL}, {@code USER}).
   */
  public static String variableName(String alias, String field) {
    return "IDB_" + alias + "_" + field;
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

  /**
   * Loads the environment: {@code file} (optional when every alias in {@code aliases} is fully
   * defined by environment variables) overlaid with the {@code IDB_<ALIAS>_*} variables.
   */
  public static EnvironmentConfig load(
      Path file, String expectedEnvironment, Collection<String> aliases, Map<String, String> env) {
    Map<String, Map<String, String>> raw = new LinkedHashMap<>();
    boolean fileExists = Files.exists(file);
    if (fileExists) {
      readFile(file, expectedEnvironment, raw);
    }
    for (String alias : aliases) {
      Names.require(alias, "database alias", "the case file");
      raw.computeIfAbsent(alias, k -> new LinkedHashMap<>());
    }
    Map<String, DatabaseSettings> result = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> entry : raw.entrySet()) {
      String alias = entry.getKey();
      Map<String, String> fields = entry.getValue();
      for (String field : FIELDS) {
        String value = env.get(variableName(alias, field));
        if (value != null && !value.isBlank()) {
          fields.put(field, value);
        }
      }
      result.put(alias, settings(alias, fields, file, fileExists));
    }
    if (result.isEmpty()) {
      throw new InitiumDbException("Environment file not found: " + file);
    }
    return new EnvironmentConfig(expectedEnvironment, Map.copyOf(result));
  }

  private static DatabaseSettings settings(
      String alias, Map<String, String> fields, Path file, boolean fileExists) {
    for (String field : FIELDS) {
      if (!fields.containsKey(field)) {
        throw new InitiumDbException(
            fileExists
                ? "Database '"
                    + alias
                    + "' is not fully defined: set it in "
                    + file
                    + " or set "
                    + variableName(alias, field)
                    + "."
                : "Environment file not found: "
                    + file
                    + " (alternatively set "
                    + variableName(alias, field)
                    + ").");
      }
    }
    String engine = fields.get("ENGINE");
    Engines.named(engine);
    return new DatabaseSettings(
        engine, fields.get("URL"), fields.get("USER"), fields.getOrDefault("CREDENTIAL", alias));
  }

  private static void readFile(
      Path file, String expectedEnvironment, Map<String, Map<String, String>> raw) {
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
    for (Map.Entry<String, JsonNode> entry : databases.properties()) {
      Names.require(entry.getKey(), "database alias", file.toString());
      JsonNode node = entry.getValue();
      if (!(node instanceof ObjectNode)) {
        throw new InitiumDbException(
            "Database '" + entry.getKey() + "' in " + file + " must be a mapping.");
      }
      Map<String, String> fields = new LinkedHashMap<>();
      put(fields, "ENGINE", YamlFiles.optionalText(node, "engine", file));
      put(fields, "URL", YamlFiles.optionalText(node, "jdbcUrl", file));
      put(fields, "USER", YamlFiles.optionalText(node, "username", file));
      String credential = YamlFiles.optionalText(node, "credential", file);
      if (credential != null) {
        Names.require(credential, "credential name", file.toString());
        fields.put("CREDENTIAL", credential);
      }
      raw.put(entry.getKey(), fields);
    }
  }

  private static void put(Map<String, String> fields, String name, String value) {
    if (value != null) {
      fields.put(name, value);
    }
  }
}
