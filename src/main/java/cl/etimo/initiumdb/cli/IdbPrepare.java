package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.PrepareService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/** Entry point of {@code idb-prepare}. Normally run by CI/CD before the test suite. */
public final class IdbPrepare {

  static final String USAGE =
      "Usage: idb-prepare --case path/to/case-dir --environment qa [--config-dir dir]"
          + " [--refresh optimized|dbunit]";

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
    return DataCommand.run(
        "idb-prepare",
        USAGE,
        java.util.Set.of("--refresh"),
        (caseConfig, environment, resolver, stdout, cli) ->
            PrepareService.run(
                caseConfig,
                environment,
                resolver,
                stdout,
                PrepareService.RefreshMode.parse(cli.value("--refresh"))),
        args,
        env,
        homeDir,
        out,
        err);
  }
}
