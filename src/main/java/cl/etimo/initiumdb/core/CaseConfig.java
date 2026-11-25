package cl.etimo.initiumdb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A case: a directory holding {@code case.yml}, which lists the database aliases in execution
 * order. The directory defines the case (its name is the directory name) and every file name is
 * fixed by convention: for each alias {@code <ALIAS>.queries.yml}, {@code <ALIAS>.dataset.xml} and
 * the optional hooks {@code <ALIAS>.pre.sql} and {@code <ALIAS>.post.sql} (DEC-009).
 */
public record CaseConfig(String name, Path dir, List<DatabaseStep> databases) {

  /** Name of the case file inside the case directory. */
  public static final String FILE = "case.yml";

  /**
   * One alias of the case. Every path is the conventional one; the file may not exist (hooks are
   * skipped when absent, and capture or prepare reports a missing queries or dataset file).
   */
  public record DatabaseStep(String alias, Path queries, Path dataset, Path preSql, Path postSql) {}

  /** The conventional file of a database inside a case directory, e.g. {@code CRM.pre.sql}. */
  public static Path file(Path caseDir, String alias, String suffix) {
    return caseDir.resolve(alias + suffix);
  }

  public static CaseConfig load(Path caseDir) {
    Path dir = caseDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(dir)) {
      throw new InitiumDbException(
          "Case directory not found: "
              + dir
              + " (a case is a directory that contains "
              + FILE
              + ").");
    }
    Path file = dir.resolve(FILE);
    ObjectNode root = YamlFiles.readObject(file, "Case file");
    YamlFiles.requireVersion(root, file);
    JsonNode databases = root.get("databases");
    if (databases == null || !databases.isArray() || databases.isEmpty()) {
      throw new InitiumDbException("'databases' in " + file + " must be a non-empty list.");
    }
    List<DatabaseStep> steps = new ArrayList<>();
    for (JsonNode step : databases) {
      if (!(step instanceof ObjectNode)) {
        throw new InitiumDbException(
            "Every entry of 'databases' in " + file + " must be a mapping.");
      }
      String alias = YamlFiles.requiredText(step, "alias", file);
      Names.require(alias, "database alias", file.toString());
      steps.add(
          new DatabaseStep(
              alias,
              file(dir, alias, ".queries.yml"),
              file(dir, alias, ".dataset.xml"),
              file(dir, alias, ".pre.sql"),
              file(dir, alias, ".post.sql")));
    }
    return new CaseConfig(String.valueOf(dir.getFileName()), dir, List.copyOf(steps));
  }
}
