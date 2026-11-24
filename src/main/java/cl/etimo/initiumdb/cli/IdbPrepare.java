package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.PrepareService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/** Entry point of {@code idb-prepare}. Normally run by CI/CD before the test suite. */
public final class IdbPrepare {

  static final String USAGE =
      "Usage: idb-prepare --case path/to/case-dir --environment qa [--config-dir dir]";

  private IdbPrepare() {}

  public static void main(String[] args) {
    System.exit(
        run(
            args,
            System.getenv(),
            Path.of(System.getProperty("user.home")),
            System.out,
            System.err));
  }

  /** Runs the command and returns the exit code: {@code 0} on success, {@code 1} on failure. */
  public static int run(
      String[] args, Map<String, String> env, Path homeDir, PrintStream out, PrintStream err) {
    return DataCommand.run("idb-prepare", USAGE, PrepareService::run, args, env, homeDir, out, err);
  }
}
