package cl.etimo.initiumdb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A case definition ({@code case.yml}): what to capture and prepare, in declared order. */
public record CaseConfig(String name, List<DatabaseStep> databases) {

  /**
   * One database step. Paths are resolved relative to the case file; {@code queries}, {@code
   * preSql} and {@code postSql} are {@code null} when not declared.
   */
  public record DatabaseStep(String alias, Path queries, Path dataset, Path preSql, Path postSql) {}

  public static CaseConfig load(Path caseFile) {
    Path file = caseFile.toAbsolutePath();
    Path dir = file.getParent();
    ObjectNode root = YamlFiles.readObject(file, "Case file");
    YamlFiles.requireVersion(root, file);
    String name = YamlFiles.requiredText(root, "name", file);
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
      Path dataset = path(dir, step, "dataset", file);
      if (dataset == null) {
        throw new InitiumDbException("Every database step in " + file + " needs a 'dataset'.");
      }
      steps.add(
          new DatabaseStep(
              YamlFiles.requiredText(step, "alias", file),
              path(dir, step, "queries", file),
              dataset,
              path(dir, step, "preSql", file),
              path(dir, step, "postSql", file)));
    }
    return new CaseConfig(name, List.copyOf(steps));
  }

  private static Path path(Path dir, JsonNode step, String field, Path file) {
    String value = YamlFiles.optionalText(step, field, file);
    return value == null ? null : dir.resolve(value).normalize();
  }
}
