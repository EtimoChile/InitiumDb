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
  private final Map<String, java.util.List<String>> lists = new HashMap<>();

  private CliArgs() {}

  static CliArgs parse(String[] args, Set<String> valueOptions, Set<String> flagOptions) {
    return parse(args, valueOptions, flagOptions, Set.of());
  }

  /**
   * Like {@link #parse(String[], Set, Set)}; options in {@code repeatable} may be given many times.
   */
  static CliArgs parse(
      String[] args, Set<String> valueOptions, Set<String> flagOptions, Set<String> repeatable) {
    CliArgs parsed = new CliArgs();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (flagOptions.contains(arg)) {
        parsed.flags.add(arg);
      } else if (valueOptions.contains(arg) || repeatable.contains(arg)) {
        if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
          throw new InitiumDbException("Option " + arg + " requires a value.");
        }
        String given = args[++i];
        if (repeatable.contains(arg)) {
          parsed.lists.computeIfAbsent(arg, k -> new java.util.ArrayList<>()).add(given);
        } else if (parsed.values.put(arg, given) != null) {
          throw new InitiumDbException("Option " + arg + " given more than once.");
        }
      } else {
        throw new InitiumDbException("Unknown argument: " + arg);
      }
    }
    return parsed;
  }

  /** Returns {@code true} when the command line asks for help ({@code --help} or {@code -h}). */
  static boolean isHelp(String[] args) {
    return java.util.Arrays.stream(args).anyMatch(a -> a.equals("--help") || a.equals("-h"));
  }

  /** Returns {@code true} when the command line asks for the version ({@code --version}). */
  static boolean isVersion(String[] args) {
    return java.util.Arrays.asList(args).contains("--version");
  }

  /** Returns every value of a repeatable option, in command-line order. */
  java.util.List<String> values(String name) {
    return lists.getOrDefault(name, java.util.List.of());
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
