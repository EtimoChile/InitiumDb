package cl.etimo.initiumdb.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an SQL hook script into statements.
 *
 * <p>Statements end with {@code ;}. Semicolons inside single-quoted literals, double-quoted
 * identifiers, line comments and block comments do not split. Procedural blocks (PL/SQL,
 * dollar-quoted bodies) are not supported by design (DEC-007): they would be split at their inner
 * semicolons.
 */
public final class SqlScript {

  private SqlScript() {}

  public static List<String> statements(String script) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int i = 0;
    int n = script.length();
    while (i < n) {
      char c = script.charAt(i);
      if (c == '\'' || c == '"') {
        i = copyQuoted(script, i, current);
      } else if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
        while (i < n && script.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
        int end = script.indexOf("*/", i + 2);
        if (end < 0) {
          throw new InitiumDbException("Unterminated comment in SQL script.");
        }
        current.append(' ');
        i = end + 2;
      } else if (c == ';') {
        add(statements, current);
        i++;
      } else {
        current.append(c);
        i++;
      }
    }
    add(statements, current);
    return statements;
  }

  /** Copies a single-quoted literal or double-quoted identifier; a doubled quote is an escape. */
  private static int copyQuoted(String script, int start, StringBuilder out) {
    char quote = script.charAt(start);
    int n = script.length();
    out.append(quote);
    int i = start + 1;
    while (i < n) {
      char d = script.charAt(i++);
      out.append(d);
      if (d == quote) {
        if (i < n && script.charAt(i) == quote) {
          out.append(quote);
          i++;
        } else {
          return i;
        }
      }
    }
    throw new InitiumDbException(
        quote == '"'
            ? "Unterminated quoted identifier in SQL script."
            : "Unterminated string literal in SQL script.");
  }

  private static void add(List<String> statements, StringBuilder current) {
    String statement = current.toString().strip();
    current.setLength(0);
    if (!statement.isEmpty()) {
      statements.add(statement);
    }
  }
}
