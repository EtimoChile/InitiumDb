package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.ConfigLayout;
import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.core.MasterKey;
import cl.etimo.initiumdb.core.ProjectInitializer;
import cl.etimo.initiumdb.db.Engines;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Entry point of {@code idb-init}. */
public final class IdbInit {

  static final String USAGE =
      """
      Usage: idb-init --case path/to/case-dir --environment dev \
      --database [ENGINE:]NAME [--database [ENGINE:]NAME ...] \
      [--engine oracle|postgresql] [--config-dir dir]
        ENGINE is oracle or postgresql (default: --engine, which defaults to oracle).
        NAME is upper-cased automatically; example: --database oracle:CRM --database postgresql:BILLING
        The case is a directory holding case.yml; existing files are never overwritten.\
      """;

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
    if (CliArgs.isHelp(args)) {
      out.println(USAGE);
      return 0;
    }
    try {
      CliArgs cli =
          CliArgs.parse(
              args,
              Set.of("--case", "--environment", "--config-dir", "--engine"),
              Set.of(),
              Set.of("--database"));
      Path caseDir = Path.of(cli.require("--case"));
      String configDir = cli.value("--config-dir");
      ConfigLayout layout =
          ConfigLayout.of(
              cli.require("--environment"), configDir != null ? Path.of(configDir) : null, homeDir);
      String engine = cli.value("--engine") != null ? cli.value("--engine") : "oracle";
      Engines.named(engine);
      List<ProjectInitializer.Database> databases =
          ProjectInitializer.databases(cli.values("--database"), engine);
      MasterKey.loadOrCreate(env, homeDir);
      ProjectInitializer.Report report = ProjectInitializer.init(caseDir, layout, databases);
      report.created().forEach(p -> out.println("Created: " + p));
      report.updated().forEach(p -> out.println("Updated: " + p));
      report.unchanged().forEach(p -> out.println("Unchanged: " + p));
      return 0;
    } catch (InitiumDbException e) {
      err.println("idb-init: " + e.getMessage());
      err.println(USAGE);
      return 1;
    }
  }
}
