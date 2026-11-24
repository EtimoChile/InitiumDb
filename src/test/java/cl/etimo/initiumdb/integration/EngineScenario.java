package cl.etimo.initiumdb.integration;

import cl.etimo.initiumdb.cli.IdbCapture;
import cl.etimo.initiumdb.cli.IdbPrepare;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Test helper for the engine integration tests: a project directory wired to one disposable
 * database through the public CLI entry points. The test supplies the engine, JDBC URL, user and
 * the {@code queries.yml} content.
 */
public final class EngineScenario {

  /** Exit code and captured output of one command. */
  public record Result(int exitCode, String out, String err) {}

  private final Path project;
  private final Path configDir;
  private final Path home;
  private final Map<String, String> env;

  public EngineScenario(
      Path root, String engine, String jdbcUrl, String username, String password, String queries)
      throws IOException {
    project = root.resolve("project");
    configDir = root.resolve("cfg");
    home = root.resolve("home");
    env = Map.of("IDB_CREDENTIAL_CRM", password);
    write(
        configDir.resolve("dev.yml"),
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: "
            + engine
            + "\n    jdbcUrl: \""
            + jdbcUrl
            + "\"\n    username: \""
            + username
            + "\"\n");
    write(
        project.resolve("case.yml"),
        "version: 1\nname: it\ndatabases:\n  - alias: CRM\n    queries: db/CRM/queries.yml\n"
            + "    dataset: db/CRM/dataset.xml\n");
    write(project.resolve("db/CRM/queries.yml"), queries);
  }

  public Path dataset() {
    return project.resolve("db/CRM/dataset.xml");
  }

  public String readDataset() throws IOException {
    return Files.readString(dataset(), StandardCharsets.UTF_8);
  }

  public Result capture() {
    return run(true);
  }

  public Result prepare() {
    return run(false);
  }

  private Result run(boolean capture) {
    String[] args = {
      "--case",
      project.resolve("case.yml").toString(),
      "--environment",
      "dev",
      "--config-dir",
      configDir.toString()
    };
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      code =
          capture ? IdbCapture.run(args, env, home, o, e) : IdbPrepare.run(args, env, home, o, e);
    }
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }
}
