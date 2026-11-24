package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.ConfigLayout;
import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.core.MasterKey;
import cl.etimo.initiumdb.core.SecretsFile;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Entry point of {@code idb-crypt}. */
public final class IdbCrypt {

  static final String USAGE =
      "Usage: idb-crypt --environment dev [--case path/to/case-dir] [--config-dir dir]";

  private IdbCrypt() {}

  public static void main(String[] args) {
    System.exit(
        run(
            args,
            System.getenv(),
            Path.of(System.getProperty("user.home")),
            System.out,
            System.err));
  }

  /**
   * Runs the command and returns the exit code: {@code 0} on success, {@code 1} on failure.
   *
   * <p>{@code --case} is accepted so an {@code idb-prepare} command line can be reused as is; the
   * secrets file depends only on the environment, so its value is not read.
   */
  public static int run(
      String[] args, Map<String, String> env, Path homeDir, PrintStream out, PrintStream err) {
    if (CliArgs.isHelp(args)) {
      out.println(USAGE);
      return 0;
    }
    try {
      CliArgs cli =
          CliArgs.parse(args, Set.of("--case", "--environment", "--config-dir"), Set.of());
      String configDir = cli.value("--config-dir");
      ConfigLayout layout =
          ConfigLayout.of(
              cli.require("--environment"), configDir != null ? Path.of(configDir) : null, homeDir);
      byte[] key = MasterKey.loadOrCreate(env, homeDir);
      boolean updated = SecretsFile.encryptInPlace(layout.secretsFile(), key);
      out.println(
          (updated ? "Secrets encrypted: " : "No cleartext secrets to encrypt: ")
              + layout.secretsFile());
      return 0;
    } catch (InitiumDbException e) {
      err.println("idb-crypt: " + e.getMessage());
      err.println(USAGE);
      return 1;
    }
  }
}
