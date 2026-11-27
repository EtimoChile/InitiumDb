package cl.etimo.initiumdb.integration;

import cl.etimo.initiumdb.cli.IdbCapture;
import cl.etimo.initiumdb.cli.IdbCrypt;
import cl.etimo.initiumdb.cli.IdbPrepare;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test helper for the shipped examples ({@code examples/<engine>}): a verbatim copy of one example
 * directory, used as README.md > "Directory layout" tells a user to. The test only points the
 * example's {@code CRM} alias at a disposable database through the documented {@code
 * IDB_CRM_URL}/{@code IDB_CRM_USER} overrides and writes the password into {@code
 * dev.secrets.json}, copied from {@code secrets.example.json}.
 */
public final class ExampleCase {

  /** Exit code and captured output of one command. */
  public record Result(int exitCode, String out, String err) {}

  private final Path example;
  private final Path home;
  private final Map<String, String> env;

  /** Copies {@code examples/<engine>} under {@code root}; the example files are not changed. */
  public ExampleCase(Path root, String engine, String jdbcUrl, String username) throws IOException {
    Path source = Path.of("examples", engine);
    if (!Files.isDirectory(source)) {
      throw new IllegalStateException("Example not found: " + source.toAbsolutePath());
    }
    example = root.resolve(engine);
    home = root.resolve("home");
    env = Map.of("IDB_CRM_URL", jdbcUrl, "IDB_CRM_USER", username);
    copyTree(source, example);
  }

  /** The example's {@code schema.sql}. */
  public Path schema() {
    return example.resolve("schema.sql");
  }

  public Path configDir() {
    return example.resolve("config");
  }

  public Path caseDir() {
    return example.resolve("testdata").resolve("regression");
  }

  public Path dataset() {
    return caseDir().resolve("CRM.dataset.xml");
  }

  public String readDataset() throws IOException {
    return Files.readString(dataset(), StandardCharsets.UTF_8);
  }

  /**
   * README.md > Directory layout: copy {@code secrets.example.json} to {@code dev.secrets.json},
   * replace the placeholder with {@code password} ({@code null} keeps the placeholder).
   */
  public void writeSecrets(String password) throws IOException {
    String content =
        Files.readString(configDir().resolve("secrets.example.json"), StandardCharsets.UTF_8);
    if (password != null) {
      content =
          content.replace(
              "replace-with-the-password-then-run-idb-crypt", password.replace("\"", "\\\""));
    }
    Files.writeString(configDir().resolve("dev.secrets.json"), content, StandardCharsets.UTF_8);
  }

  public String readSecrets() throws IOException {
    return Files.readString(configDir().resolve("dev.secrets.json"), StandardCharsets.UTF_8);
  }

  /** {@code idb-crypt --environment dev --config-dir <example>/config}. */
  public Result crypt() {
    return run(
        (args, o, e) -> IdbCrypt.run(args, env, home, o, e),
        "--environment",
        "dev",
        "--config-dir",
        configDir().toString());
  }

  /**
   * {@code idb-capture --case <example>/testdata/regression --environment dev --config-dir ...}.
   */
  public Result capture() {
    return run((args, o, e) -> IdbCapture.run(args, env, home, o, e), dataArgs());
  }

  /**
   * {@code idb-prepare --case <example>/testdata/regression --environment dev --config-dir ...}.
   */
  public Result prepare() {
    return run((args, o, e) -> IdbPrepare.run(args, env, home, o, e), dataArgs());
  }

  private String[] dataArgs() {
    return new String[] {
      "--case", caseDir().toString(), "--environment", "dev", "--config-dir", configDir().toString()
    };
  }

  private interface Command {
    int run(String[] args, PrintStream out, PrintStream err);
  }

  private static Result run(Command command, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      code = command.run(args, o, e);
    }
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static void copyTree(Path source, Path target) throws IOException {
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path path : (Iterable<Path>) paths::iterator) {
        Path destination = target.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}
