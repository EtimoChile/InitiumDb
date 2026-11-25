package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.db.Engines;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes template configuration files; never contains credentials. YAML and JSON files are read and
 * written with the YAML and JSON libraries, never as text (so comments and layout of an existing
 * YAML file are not preserved). Initialization is cumulative: files that exist are kept and only
 * the databases they do not yet contain are added. An existing database entry is never modified.
 */
public final class ProjectInitializer {

  /** A database to initialize: its upper-case alias and engine. */
  public record Database(String alias, String engine) {}

  /** Files created, files extended with new databases and files that needed nothing. */
  public record Report(List<Path> created, List<Path> updated, List<Path> unchanged) {}

  private record Change(Path file, String content, boolean existed) {}

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private ProjectInitializer() {}

  /**
   * Parses one {@code --alias} argument: {@code [engine:]NAME}. The name is converted to upper
   * case, the engine to lower case; without a prefix {@code defaultEngine} applies.
   */
  public static Database database(String argument, String defaultEngine) {
    int colon = argument.indexOf(':');
    String engine =
        colon < 0 ? defaultEngine : argument.substring(0, colon).toLowerCase(Locale.ROOT);
    String alias = (colon < 0 ? argument : argument.substring(colon + 1)).toUpperCase(Locale.ROOT);
    Names.require(alias, "alias", null);
    Engines.named(engine);
    return new Database(alias, engine);
  }

  /** Parses every {@code --alias} argument; at least one, names unique after upper-casing. */
  public static List<Database> databases(List<String> arguments, String defaultEngine) {
    if (arguments.isEmpty()) {
      throw new InitiumDbException("At least one alias is required (--alias [ENGINE:]NAME).");
    }
    List<Database> result = arguments.stream().map(a -> database(a, defaultEngine)).toList();
    if (result.stream().map(Database::alias).distinct().count() != result.size()) {
      throw new InitiumDbException("Aliases must be unique.");
    }
    return result;
  }

  /**
   * Creates what is missing and adds the databases that are not yet present to {@code case.yml} (in
   * the case directory), the environment file and the secrets file; creates, in the case directory,
   * the {@code <ALIAS>.queries.yml} and the empty hook files of every given database when absent.
   * Nothing is written when any existing file cannot be read or extended.
   */
  public static Report init(Path caseDirectory, ConfigLayout layout, List<Database> databases) {
    Path caseDir = caseDirectory.toAbsolutePath().normalize();
    Path caseFile = caseDir.resolve(CaseConfig.FILE);
    List<Change> changes = new ArrayList<>();
    List<Path> unchanged = new ArrayList<>();

    plan(caseFile, planCase(caseFile, databases), changes, unchanged);
    for (Database db : databases) {
      Path queries = CaseConfig.file(caseDir, db.alias(), ".queries.yml");
      plan(
          queries,
          Files.exists(queries) ? null : YamlFiles.write(queriesTemplate()),
          changes,
          unchanged);
      for (String hook : List.of(".pre.sql", ".post.sql")) {
        Path file = CaseConfig.file(caseDir, db.alias(), hook);
        plan(file, Files.exists(file) ? null : "", changes, unchanged);
      }
    }
    Path environmentFile = layout.environmentFile();
    plan(environmentFile, planEnvironment(environmentFile, layout, databases), changes, unchanged);
    Path secretsFile = layout.secretsFile();
    List<String> names = databases.stream().map(Database::alias).toList();
    plan(
        secretsFile,
        Files.exists(secretsFile)
            ? SecretsFile.withMissing(secretsFile, names)
            : SecretsFile.create(secretsFile, names),
        changes,
        unchanged);

    List<Path> created = new ArrayList<>();
    List<Path> updated = new ArrayList<>();
    for (Change change : changes) {
      write(change.file(), change.content());
      (change.existed() ? updated : created).add(change.file());
    }
    return new Report(created, updated, unchanged);
  }

  private static void plan(Path file, String content, List<Change> changes, List<Path> unchanged) {
    if (content == null) {
      unchanged.add(file);
    } else {
      changes.add(new Change(file, content, Files.exists(file)));
    }
  }

  private static String planCase(Path file, List<Database> databases) {
    ObjectNode root;
    ArrayNode steps;
    if (Files.exists(file)) {
      root = YamlFiles.readObject(file, "Case file");
      YamlFiles.requireVersion(root, file);
      if (!(root.get("databases") instanceof ArrayNode existing)) {
        throw new InitiumDbException("'databases' in " + file + " must be a list.");
      }
      steps = existing;
    } else {
      root = NODES.objectNode();
      root.put("version", 1);
      steps = root.putArray("databases");
    }
    Set<String> present = new HashSet<>();
    for (JsonNode step : steps) {
      JsonNode alias = step.get("alias");
      if (alias != null && alias.isTextual()) {
        present.add(alias.asText());
      }
    }
    boolean added = false;
    for (Database db : databases) {
      if (present.add(db.alias())) {
        ObjectNode step = steps.addObject();
        step.put("alias", db.alias());
        added = true;
      }
    }
    return added || !Files.exists(file) ? YamlFiles.write(root) : null;
  }

  private static String planEnvironment(Path file, ConfigLayout layout, List<Database> databases) {
    ObjectNode root;
    ObjectNode entries;
    if (Files.exists(file)) {
      root = YamlFiles.readObject(file, "Environment file");
      YamlFiles.requireVersion(root, file);
      String declared = YamlFiles.requiredText(root, "environment", file);
      if (!declared.equals(layout.environment())) {
        throw new InitiumDbException(
            "Environment file "
                + file
                + " declares environment '"
                + declared
                + "', expected '"
                + layout.environment()
                + "'.");
      }
      entries = YamlFiles.requiredObject(root, "databases", file);
    } else {
      root = NODES.objectNode();
      root.put("version", 1);
      root.put("environment", layout.environment());
      entries = root.putObject("databases");
    }
    boolean added = false;
    for (Database db : databases) {
      if (!entries.has(db.alias())) {
        ObjectNode entry = entries.putObject(db.alias());
        entry.put("engine", db.engine());
        entry.put(
            "jdbcUrl",
            "postgresql".equals(db.engine())
                ? "jdbc:postgresql://HOST:5432/DATABASE"
                : "jdbc:oracle:thin:@//HOST:1521/SERVICE");
        entry.put("username", "USERNAME");
        added = true;
      }
    }
    return added || !Files.exists(file) ? YamlFiles.write(root) : null;
  }

  private static ObjectNode queriesTemplate() {
    ObjectNode root = NODES.objectNode();
    root.put("schema", "SCHEMA_NAME");
    ObjectNode table = root.putArray("tables").addObject();
    table.put("name", "TABLE_NAME");
    table.put("sql", "SELECT * FROM TABLE_NAME ORDER BY ID");
    return root;
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new InitiumDbException("Unable to write " + file + ".");
    }
  }
}
