package cl.etimo.initiumdb.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Capture queries ({@code queries.yml}): an optional default schema and ordered tables. */
public record QueriesConfig(String schema, List<TableQuery> tables) {

  /** One table to capture, with the {@code SELECT} that selects its rows. */
  public record TableQuery(String name, String sql) {}

  public static QueriesConfig load(Path file) {
    ObjectNode root = YamlFiles.readObject(file, "Queries file");
    String schema = YamlFiles.optionalText(root, "schema", file);
    JsonNode tables = root.get("tables");
    if (tables == null || !tables.isArray() || tables.isEmpty()) {
      throw new InitiumDbException("'tables' in " + file + " must be a non-empty list.");
    }
    List<TableQuery> queries = new ArrayList<>();
    for (JsonNode table : tables) {
      if (!(table instanceof ObjectNode)) {
        throw new InitiumDbException("Every entry of 'tables' in " + file + " must be a mapping.");
      }
      queries.add(
          new TableQuery(
              YamlFiles.requiredText(table, "name", file),
              YamlFiles.requiredText(table, "sql", file)));
    }
    return new QueriesConfig(schema, List.copyOf(queries));
  }
}
