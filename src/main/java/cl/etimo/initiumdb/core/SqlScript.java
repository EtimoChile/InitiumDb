package cl.etimo.initiumdb.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an SQL hook script into statements.
 *
 * <p>Statements end with {@code ;}. Semicolons inside single-quoted literals, double-quoted
 * identifiers, line comments and block comments do not split. Comments are kept in the statement
 * exactly as written, because they can be meaningful to the database (optimizer hints, for
 * example); a chunk made only of comments and whitespace is not a statement and is dropped.
 * Procedural blocks (PL/SQL, dollar-quoted bodies) are not supported by design (DEC-007): they
 * would be split at their inner semicolons.
 *
 * <p>Conditional execution (DEC-010) is a line comment before a statement:
 *
 * <pre>
 * -- idb:skip-if SELECT 1 FROM app_config WHERE k = 'seeded'
 * INSERT INTO app_config (k, v) VALUES ('seeded', '1');
 * </pre>
 *
 * {@code idb:run-if} runs the statement only when the guard query returns a row, {@code
 * idb:skip-if} runs it only when the guard returns none. The guard is the rest of the line, with no
 * semicolon needed. A directive applies to the next statement only, and the directive line is not
 * sent to the database. Every other comment, including block comments, has no special meaning. To
 * keep a typo from silently running a statement that was meant to be conditional, any line comment
 * starting with {@code idb:} must be a valid directive, with one guard, before the statement.
 *
 * <p>{@code idb:set <query>} (DEC-020) stands alone between statements: it becomes an assignment
 * ({@link Statement#assignment()}) whose {@code sql} is the query that sets hook variables.
 */
public final class SqlScript {

  /** Runs the statement when a row is returned ({@code runIf}) or when none is. */
  public record Guard(boolean runIf, String query) {}

  /**
   * One statement to execute; {@code guard} is {@code null} when it is unconditional. An {@code
   * assignment} is an {@code idb:set} directive: {@code sql} is its query and it has no guard.
   */
  public record Statement(String sql, Guard guard, boolean assignment) {}

  private static final String SET = "idb:set";

  private SqlScript() {}

  public static List<Statement> statements(String script) {
    List<Statement> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    Guard[] pending = new Guard[1];
    int i = 0;
    int n = script.length();
    while (i < n) {
      char c = script.charAt(i);
      if (c == '\'' || c == '"') {
        i = copyQuoted(script, i, current);
      } else if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
        int end = script.indexOf('\n', i);
        end = end < 0 ? n : end;
        String comment = script.substring(i, end);
        String assignment = assignment(comment);
        Guard guard = assignment == null ? directive(comment) : null;
        if (assignment != null) {
          if (pending[0] != null) {
            throw new InitiumDbException(
                "An idb:run-if or idb:skip-if directive must be followed by its statement, not by "
                    + SET
                    + ".");
          }
          if (hasCode(current.toString())) {
            throw new InitiumDbException(
                SET + " must come between statements, not inside one: " + comment.strip());
          }
          statements.add(new Statement(assignment, null, true));
        } else if (guard == null) {
          current.append(comment);
        } else {
          if (pending[0] != null) {
            throw new InitiumDbException("Only one idb directive is allowed per statement.");
          }
          if (hasCode(current.toString())) {
            throw new InitiumDbException(
                "An idb directive must come before the statement it controls: " + comment.strip());
          }
          pending[0] = guard;
        }
        i = end;
      } else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
        int end = script.indexOf("*/", i + 2);
        if (end < 0) {
          throw new InitiumDbException("Unterminated comment in SQL script.");
        }
        current.append(script, i, end + 2);
        i = end + 2;
      } else if (c == ';') {
        add(statements, current, pending);
        i++;
      } else {
        current.append(c);
        i++;
      }
    }
    add(statements, current, pending);
    return statements;
  }

  /** Returns the query of an {@code idb:set} line comment, or {@code null} for any other. */
  private static String assignment(String comment) {
    String body = comment.substring(2).strip();
    int space = indexOfWhitespace(body);
    if (!(space < 0 ? body : body.substring(0, space)).equals(SET)) {
      return null;
    }
    String query = query(body, space);
    if (query.isEmpty()) {
      throw new InitiumDbException("Directive " + SET + " needs a query on the same line.");
    }
    return query;
  }

  /** Returns the guard of an {@code idb:} line comment, or {@code null} for any other comment. */
  private static Guard directive(String comment) {
    String body = comment.substring(2).strip();
    if (!body.startsWith("idb:")) {
      return null;
    }
    int space = indexOfWhitespace(body);
    String name = space < 0 ? body : body.substring(0, space);
    boolean runIf;
    if (name.equals("idb:run-if")) {
      runIf = true;
    } else if (name.equals("idb:skip-if")) {
      runIf = false;
    } else {
      throw new InitiumDbException(
          "Unknown directive '" + name + "' (expected idb:run-if, idb:skip-if or idb:set).");
    }
    String query = query(body, space);
    if (query.isEmpty()) {
      throw new InitiumDbException("Directive " + name + " needs a guard query on the same line.");
    }
    return new Guard(runIf, query);
  }

  /** The rest of the directive line after its name, without a trailing {@code ;}. */
  private static String query(String body, int space) {
    String query = space < 0 ? "" : body.substring(space).strip();
    if (query.endsWith(";")) {
      query = query.substring(0, query.length() - 1).strip();
    }
    return query;
  }

  private static int indexOfWhitespace(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (Character.isWhitespace(text.charAt(i))) {
        return i;
      }
    }
    return -1;
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

  private static void add(List<Statement> statements, StringBuilder current, Guard[] pending) {
    String sql = current.toString().strip();
    current.setLength(0);
    Guard guard = pending[0];
    pending[0] = null;
    if (hasCode(sql)) {
      statements.add(new Statement(sql, guard, false));
    } else if (guard != null) {
      throw new InitiumDbException(
          "Directive idb:"
              + (guard.runIf() ? "run-if" : "skip-if")
              + " has no statement after it.");
    }
  }

  /** Returns {@code true} when the text has something other than whitespace and comments. */
  private static boolean hasCode(String text) {
    int i = 0;
    int n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (text.startsWith("--", i)) {
        int eol = text.indexOf('\n', i);
        i = eol < 0 ? n : eol + 1;
      } else if (text.startsWith("/*", i)) {
        i = text.indexOf("*/", i + 2) + 2;
      } else {
        return true;
      }
    }
    return false;
  }
}
