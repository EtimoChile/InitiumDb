package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.ConfigLayout;
import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.core.MasterKey;
import cl.etimo.initiumdb.core.ProjectInitializer;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Entry point of {@code idb-init}. */
public final class IdbInit {

  static final String USAGE =
      "Usage: idb-init --case path/to/case.yml --environment dev"
          + " [--config-dir dir] [--overwrite]";

  private IdbInit() {}

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
    try {
      CliArgs cli =
          CliArgs.parse(
              args, Set.of("--case", "--environment", "--config-dir"), Set.of("--overwrite"));
      Path caseFile = Path.of(cli.require("--case"));
      String configDir = cli.value("--config-dir");
      ConfigLayout layout =
          ConfigLayout.of(
              cli.require("--environment"), configDir != null ? Path.of(configDir) : null, homeDir);
      MasterKey.loadOrCreate(env, homeDir);
      ProjectInitializer.Report report =
          ProjectInitializer.init(caseFile, layout, cli.flag("--overwrite"));
      report.written().forEach(p -> out.println("Created: " + p));
      report.skipped().forEach(p -> out.println("Skipped (exists): " + p));
      return 0;
    } catch (InitiumDbException e) {
      err.println("idb-init: " + e.getMessage());
      err.println(USAGE);
      return 1;
    }
  }
}
