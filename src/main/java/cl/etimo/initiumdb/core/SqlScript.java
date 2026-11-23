package cl.etimo.initiumdb.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an SQL hook script into statements.
 *
 * <p>Statements end with {@code ;}. Semicolons inside single-quoted literals, line comments and
 * block comments do not split. PL/SQL blocks and dollar-quoted bodies are not supported: they would
 * be split at their inner semicolons.
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
      if (c == '\'') {
        i = copyLiteral(script, i, current);
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

  private static int copyLiteral(String script, int start, StringBuilder out) {
    int n = script.length();
    out.append('\'');
    int i = start + 1;
    while (i < n) {
      char d = script.charAt(i++);
      out.append(d);
      if (d == '\'') {
        if (i < n && script.charAt(i) == '\'') {
          out.append('\'');
          i++;
        } else {
          return i;
        }
      }
    }
    throw new InitiumDbException("Unterminated string literal in SQL script.");
  }

  private static void add(List<String> statements, StringBuilder current) {
    String statement = current.toString().strip();
    current.setLength(0);
    if (!statement.isEmpty()) {
      statements.add(statement);
    }
  }
}
