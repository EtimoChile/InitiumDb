package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.db.Engines;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes template configuration files; never contains credentials. Initialization is cumulative:
 * files that exist are kept and only the databases they do not yet contain are added. An existing
 * database entry is never modified.
 */
public final class ProjectInitializer {

  /** A database to initialize: its upper-case alias and engine. */
  public record Database(String alias, String engine) {}

  /** Files created, files extended with new databases and files that needed nothing. */
  public record Report(List<Path> created, List<Path> updated, List<Path> unchanged) {}

  private record Change(Path file, String content, boolean existed) {}

  private static final String QUERIES_TEMPLATE =
      """
      schema: SCHEMA_NAME
      tables:
        - name: TABLE_NAME
          sql: |
            SELECT * FROM TABLE_NAME ORDER BY ID
      """;

  private ProjectInitializer() {}

  /**
   * Parses one {@code --database} argument: {@code [engine:]NAME}. The name is converted to upper
   * case, the engine to lower case; without a prefix {@code defaultEngine} applies.
   */
  public static Database database(String argument, String defaultEngine) {
    int colon = argument.indexOf(':');
    String engine =
        colon < 0 ? defaultEngine : argument.substring(0, colon).toLowerCase(Locale.ROOT);
    String alias = (colon < 0 ? argument : argument.substring(colon + 1)).toUpperCase(Locale.ROOT);
    Names.require(alias, "database name", null);
    Engines.named(engine);
    return new Database(alias, engine);
  }

  /** Parses every {@code --database} argument; at least one, names unique after upper-casing. */
  public static List<Database> databases(List<String> arguments, String defaultEngine) {
    if (arguments.isEmpty()) {
      throw new InitiumDbException("At least one database is required (--database [ENGINE:]NAME).");
    }
    List<Database> result = arguments.stream().map(a -> database(a, defaultEngine)).toList();
    if (result.stream().map(Database::alias).distinct().count() != result.size()) {
      throw new InitiumDbException("Database names must be unique.");
    }
    return result;
  }

  /**
   * Creates what is missing and adds the databases that are not yet present to the case, the
   * environment file and the secrets file; creates the {@code <ALIAS>.queries.yml} of each database
   * next to the case when absent. Nothing is written when any existing file cannot be extended.
   */
  public static Report init(Path caseFile, ConfigLayout layout, List<Database> databases) {
    Path absoluteCase = caseFile.toAbsolutePath();
    Path caseDir = absoluteCase.getParent();
    List<Change> changes = new ArrayList<>();
    List<Path> unchanged = new ArrayList<>();

    plan(absoluteCase, planCase(absoluteCase, databases), changes, unchanged);
    for (Database db : databases) {
      Path queries = caseDir.resolve(db.alias() + ".queries.yml");
      plan(queries, Files.exists(queries) ? null : QUERIES_TEMPLATE, changes, unchanged);
      for (String hook : List.of(".pre.sql", ".post.sql")) {
        Path file = caseDir.resolve(db.alias() + hook);
        plan(file, Files.exists(file) ? null : "", changes, unchanged);
      }
    }
    Path environmentFile = layout.environmentFile();
    plan(environmentFile, planEnvironment(environmentFile, layout, databases), changes, unchanged);
    Path secretsFile = layout.secretsFile();
    plan(secretsFile, planSecrets(secretsFile, databases), changes, unchanged);

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
    if (!Files.exists(file)) {
      return "version: 1\nname: my-case\n\ndatabases:\n" + caseSteps(databases);
    }
    ObjectNode root = YamlFiles.readObject(file, "Case file");
    YamlFiles.requireVersion(root, file);
    Set<String> present = new HashSet<>();
    if (root.get("databases") != null) {
      for (JsonNode step : root.get("databases")) {
        JsonNode alias = step.get("alias");
        if (alias != null && alias.isTextual()) {
          present.add(alias.asText());
        }
      }
    }
    return append(file, databases, present, caseSteps(missing(databases, present)), Case.INSTANCE);
  }

  private static String planEnvironment(Path file, ConfigLayout layout, List<Database> databases) {
    if (!Files.exists(file)) {
      return "version: 1\nenvironment: "
          + layout.environment()
          + "\n\ndatabases:\n"
          + environmentEntries(databases);
    }
    ObjectNode root = YamlFiles.readObject(file, "Environment file");
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
    Set<String> present = new HashSet<>();
    YamlFiles.requiredObject(root, "databases", file).fieldNames().forEachRemaining(present::add);
    return append(
        file,
        databases,
        present,
        environmentEntries(missing(databases, present)),
        Environment.INSTANCE);
  }

  private static String planSecrets(Path file, List<Database> databases) {
    List<String> names = databases.stream().map(Database::alias).toList();
    if (Files.exists(file)) {
      return SecretsFile.withMissing(file, names);
    }
    StringBuilder sb = new StringBuilder("{\n");
    for (int i = 0; i < names.size(); i++) {
      sb.append("  \"").append(names.get(i)).append("\": \"\"");
      sb.append(i < names.size() - 1 ? ",\n" : "\n");
    }
    return sb.append("}\n").toString();
  }

  /** Strategy to read the aliases a YAML file declares, used to verify an appended result. */
  private interface Layout {
    Set<String> aliases(ObjectNode root);
  }

  private enum Case implements Layout {
    INSTANCE;

    @Override
    public Set<String> aliases(ObjectNode root) {
      Set<String> result = new LinkedHashSet<>();
      if (root.get("databases") != null) {
        for (JsonNode step : root.get("databases")) {
          JsonNode alias = step.get("alias");
          if (alias != null && alias.isTextual()) {
            result.add(alias.asText());
          }
        }
      }
      return result;
    }
  }

  private enum Environment implements Layout {
    INSTANCE;

    @Override
    public Set<String> aliases(ObjectNode root) {
      Set<String> result = new LinkedHashSet<>();
      if (root.get("databases") != null) {
        root.get("databases").fieldNames().forEachRemaining(result::add);
      }
      return result;
    }
  }

  /**
   * Appends {@code addition} at the end of the file (where the {@code databases} entry must end)
   * and verifies by parsing the result; returns {@code null} when there is nothing to add.
   */
  private static String append(
      Path file, List<Database> wanted, Set<String> present, String addition, Layout layout) {
    if (missing(wanted, present).isEmpty()) {
      return null;
    }
    String text = readText(file);
    String result = (text.endsWith("\n") ? text : text + "\n") + addition;
    ObjectNode parsed = YamlFiles.tryParse(result);
    boolean ok =
        parsed != null
            && lastField(parsed).equals("databases")
            && layout
                .aliases(parsed)
                .containsAll(missing(wanted, present).stream().map(Database::alias).toList());
    if (!ok) {
      throw new InitiumDbException(
          "Cannot add databases to "
              + file
              + ": 'databases' must be the last entry of the file, as a block mapping or list."
              + " Add them manually: "
              + missing(wanted, present).stream().map(Database::alias).toList()
              + ".");
    }
    return result;
  }

  private static String lastField(ObjectNode node) {
    String last = "";
    for (var it = node.fieldNames(); it.hasNext(); ) {
      last = it.next();
    }
    return last;
  }

  private static List<Database> missing(List<Database> databases, Set<String> present) {
    return databases.stream().filter(d -> !present.contains(d.alias())).toList();
  }

  private static String caseSteps(List<Database> databases) {
    StringBuilder sb = new StringBuilder();
    for (Database db : databases) {
      String alias = db.alias();
      sb.append("  - alias: ").append(alias).append('\n');
      sb.append("    queries: ").append(alias).append(".queries.yml\n");
      sb.append("    dataset: ").append(alias).append(".dataset.xml\n");
      sb.append("    preSql: ").append(alias).append(".pre.sql\n");
      sb.append("    postSql: ").append(alias).append(".post.sql\n");
    }
    return sb.toString();
  }

  private static String environmentEntries(List<Database> databases) {
    StringBuilder sb = new StringBuilder();
    for (Database db : databases) {
      String url =
          "postgresql".equals(db.engine())
              ? "jdbc:postgresql://HOST:5432/DATABASE"
              : "jdbc:oracle:thin:@//HOST:1521/SERVICE";
      sb.append("  ").append(db.alias()).append(":\n");
      sb.append("    engine: ").append(db.engine()).append('\n');
      sb.append("    jdbcUrl: \"").append(url).append("\"\n");
      sb.append("    username: \"USERNAME\"\n");
    }
    return sb.toString();
  }

  private static String readText(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new InitiumDbException("Unable to read " + file + ".");
    }
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
