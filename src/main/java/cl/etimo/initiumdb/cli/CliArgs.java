package cl.etimo.initiumdb.cli;

import cl.etimo.initiumdb.core.InitiumDbException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Minimal {@code --name value} / {@code --flag} parser. */
final class CliArgs {

  private final Map<String, String> values = new HashMap<>();
  private final Set<String> flags = new HashSet<>();

  private CliArgs() {}

  static CliArgs parse(String[] args, Set<String> valueOptions, Set<String> flagOptions) {
    CliArgs parsed = new CliArgs();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (flagOptions.contains(arg)) {
        parsed.flags.add(arg);
      } else if (valueOptions.contains(arg)) {
        if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
          throw new InitiumDbException("Option " + arg + " requires a value.");
        }
        if (parsed.values.put(arg, args[++i]) != null) {
          throw new InitiumDbException("Option " + arg + " given more than once.");
        }
      } else {
        throw new InitiumDbException("Unknown argument: " + arg);
      }
    }
    return parsed;
  }

  boolean flag(String name) {
    return flags.contains(name);
  }

  /** Returns the option value or {@code null} when absent. */
  String value(String name) {
    return values.get(name);
  }

  String require(String name) {
    String value = values.get(name);
    if (value == null) {
      throw new InitiumDbException("Missing required option " + name + ".");
    }
    return value;
  }
}
