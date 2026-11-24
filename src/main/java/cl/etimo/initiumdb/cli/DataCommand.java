package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.CaseConfig;
import cl.etimo.initiumdb.core.ConfigLayout;
import cl.etimo.initiumdb.core.ConnectionResolver;
import cl.etimo.initiumdb.core.DefaultCredentialProvider;
import cl.etimo.initiumdb.core.EnvironmentConfig;
import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.core.JdbcConnectionResolver;
import cl.etimo.initiumdb.core.MasterKey;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Shared wiring of {@code idb-capture} and {@code idb-prepare}. */
final class DataCommand {

  /** The core operation of a command. */
  interface Action {
    void run(
        CaseConfig caseConfig,
        EnvironmentConfig environment,
        ConnectionResolver resolver,
        PrintStream out);
  }

  private DataCommand() {}

  /** Parses arguments, loads configuration and runs {@code action}; returns the exit code. */
  static int run(
      String name,
      String usage,
      Action action,
      String[] args,
      Map<String, String> env,
      Path homeDir,
      PrintStream out,
      PrintStream err) {
    if (CliArgs.isHelp(args)) {
      out.println(usage);
      return 0;
    }
    try {
      CliArgs cli =
          CliArgs.parse(args, Set.of("--case", "--environment", "--config-dir"), Set.of());
      String configDir = cli.value("--config-dir");
      ConfigLayout layout =
          ConfigLayout.of(
              cli.require("--environment"), configDir != null ? Path.of(configDir) : null, homeDir);
      CaseConfig caseConfig = CaseConfig.load(Path.of(cli.require("--case")));
      EnvironmentConfig environment =
          EnvironmentConfig.load(
              layout.environmentFile(),
              layout.environment(),
              caseConfig.databases().stream().map(s -> s.alias()).toList(),
              env);
      ConnectionResolver resolver =
          new JdbcConnectionResolver(
              environment,
              new DefaultCredentialProvider(
                  env,
                  layout.secretsFile(),
                  () -> MasterKey.load(env, homeDir),
                  () -> MasterKey.loadOrCreate(env, homeDir),
                  message -> err.println(name + ": " + message)));
      action.run(caseConfig, environment, resolver, out);
      return 0;
    } catch (InitiumDbException e) {
      err.println(name + ": " + e.getMessage());
      err.println(usage);
      return 1;
    }
  }
}
