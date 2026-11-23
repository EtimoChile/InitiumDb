package cl.etimo.initiumdb;

import cl.etimo.initiumdb.cli.IdbCrypt;
import cl.etimo.initiumdb.cli.IdbInit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Test helper: runs a CLI entry point and captures its exit code and output. */
record CliRun(int exitCode, String out, String err) {

  static CliRun init(Map<String, String> env, Path home, String... args) {
    return run(false, env, home, args);
  }

  static CliRun crypt(Map<String, String> env, Path home, String... args) {
    return run(true, env, home, args);
  }

  private static CliRun run(boolean crypt, Map<String, String> env, Path home, String[] args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      code = crypt ? IdbCrypt.run(args, env, home, o, e) : IdbInit.run(args, env, home, o, e);
    }
    return new CliRun(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }
}
