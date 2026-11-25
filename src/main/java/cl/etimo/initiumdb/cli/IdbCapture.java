package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.CaptureService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/** Entry point of {@code idb-capture}. Not part of CI/CD. */
public final class IdbCapture {

  static final String USAGE =
      "Usage: idb-capture --case path/to/case-dir --environment dev [--config-dir dir]";

  private IdbCapture() {}

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
        "idb-capture",
        USAGE,
        java.util.Set.of(),
        (caseConfig, environment, resolver, stdout, cli) ->
            CaptureService.run(caseConfig, environment, resolver, stdout),
        args,
        env,
        homeDir,
        out,
        err);
  }
}
