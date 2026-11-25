package cl.etimo.initiumdb;

import cl.etimo.initiumdb.cli.IdbCapture;
import cl.etimo.initiumdb.cli.IdbCrypt;
import cl.etimo.initiumdb.cli.IdbInit;
import cl.etimo.initiumdb.cli.IdbPrepare;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** Test helper: runs a CLI entry point and captures its exit code and output. */
record CliRun(int exitCode, String out, String err) {

  /** Signature shared by the {@code run} method of every entry point. */
  private interface Entry {
    int run(String[] args, Map<String, String> env, Path home, PrintStream out, PrintStream err);
  }

  static CliRun init(Map<String, String> env, Path home, String... args) {
    return run(IdbInit::run, env, home, args);
  }

  static CliRun crypt(Map<String, String> env, Path home, String... args) {
    return run(IdbCrypt::run, env, home, args);
  }

  static CliRun capture(Map<String, String> env, Path home, String... args) {
    return run(IdbCapture::run, env, home, args);
  }

  static CliRun prepare(Map<String, String> env, Path home, String... args) {
    return run(IdbPrepare::run, env, home, args);
  }

  private static CliRun run(Entry entry, Map<String, String> env, Path home, String[] args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      code = entry.run(args, env, home, o, e);
    }
    return new CliRun(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }
}
