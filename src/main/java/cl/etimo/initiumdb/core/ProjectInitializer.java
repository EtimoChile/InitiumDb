package cl.etimo.initiumdb.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes template configuration files; never contains credentials. */
public final class ProjectInitializer {

  /** Files written and files skipped because they already existed. */
  public record Report(List<Path> written, List<Path> skipped) {}

  private static final String CASE_TEMPLATE =
      """
      version: 1
      name: my-case

      databases:
        - alias: CRM
          queries: db/CRM/queries.yml
          dataset: db/CRM/dataset.xml
      """;

  private static final String QUERIES_TEMPLATE =
      """
      schema: APP_OWNER
      tables:
        - name: EXAMPLE_TABLE
          sql: |
            SELECT * FROM EXAMPLE_TABLE ORDER BY ID
      """;

  private static final String ENVIRONMENT_TEMPLATE =
      """
      version: 1
      environment: %s

      databases:
        CRM:
          engine: oracle
          jdbcUrl: "jdbc:oracle:thin:@//HOST:1521/SERVICE"
          username: "USERNAME"
          credential: "crm"
      """;

  private static final String SECRETS_TEMPLATE = "{\n  \"crm\": \"\"\n}\n";

  private ProjectInitializer() {}

  /**
   * Creates the case, its example queries, the environment file and an empty secrets file. Existing
   * files are skipped unless {@code overwrite} is set; the secrets file is never overwritten.
   */
  public static Report init(Path caseFile, ConfigLayout layout, boolean overwrite) {
    Path absoluteCase = caseFile.toAbsolutePath();
    Path caseDir = absoluteCase.getParent();
    List<Path> written = new ArrayList<>();
    List<Path> skipped = new ArrayList<>();
    put(absoluteCase, CASE_TEMPLATE, overwrite, written, skipped);
    put(caseDir.resolve("db/CRM/queries.yml"), QUERIES_TEMPLATE, overwrite, written, skipped);
    put(
        layout.environmentFile(),
        ENVIRONMENT_TEMPLATE.formatted(layout.environment()),
        overwrite,
        written,
        skipped);
    put(layout.secretsFile(), SECRETS_TEMPLATE, false, written, skipped);
    return new Report(written, skipped);
  }

  private static void put(
      Path file, String content, boolean overwrite, List<Path> written, List<Path> skipped) {
    try {
      if (Files.exists(file) && !overwrite) {
        skipped.add(file);
        return;
      }
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
      written.add(file);
    } catch (IOException e) {
      throw new InitiumDbException("Unable to write " + file + ".");
    }
  }
}
