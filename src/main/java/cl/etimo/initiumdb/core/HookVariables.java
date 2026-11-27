package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.db.DatabaseEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hook variables of one alias (DEC-020): set by {@code -- idb:set <query>} and referenced by later
 * statements, guards and {@code idb:set} queries of the pre and post hook.
 *
 * <ul>
 *   <li>{@code :name} is a bind variable, sent with its type ({@code NULL} allowed). It starts with
 *       a {@code :} not preceded by a letter, digit, {@code _} or {@code :} (so {@code ::} casts and
 *       {@code a[lo:hi]} slices are not binds).
 *   <li>{@code ${name}} is replaced by an SQL literal of the engine ({@link
 *       DatabaseEngine#literal}); {@code NULL} and unsupported types fail.
 *   <li>{@code $${name}} is replaced by the text as is, once verified as an identifier, optionally
 *       schema-qualified. {@code $${} wins over {@code $} followed by {@code ${}.
 * </ul>
 *
 * Nothing is replaced inside single-quoted literals or comments. Inside a double-quoted identifier
 * only {@code $${name}} is, with an unqualified identifier, so {@code "$${X}"} writes a mixed-case
 * name. Names are case-insensitive; an undefined name fails. Values are never printed.
 */
public final class HookVariables {

  /** A variable's value with the metadata of the column it was read from. */
  private record Variable(Object value, int jdbcType, String typeName) {}

  /**
   * A statement ready to run: the SQL as written in the hook, the SQL to send and the values of its
   * {@code ?} placeholders, in order ({@code null} for a NULL value).
   */
  public record Expanded(String written, String sql, List<Object> binds, List<Integer> bindTypes) {
    public boolean hasBinds() {
      return !binds.isEmpty();
    }

    /**
     * {@code e} with the SQL sent replaced by the SQL as written wherever the driver quotes it, so
     * values written as literals do not reach the output.
     */
    SQLException hidingValues(SQLException e) {
      String message = e.getMessage();
      if (message == null || sql.equals(written) || !message.contains(sql)) {
        return e;
      }
      return new SQLException(message.replace(sql, written), e.getSQLState(), e.getErrorCode());
    }
  }

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final String PART = "[A-Za-z_][A-Za-z0-9_$#]*";
  private static final Pattern IDENTIFIER = Pattern.compile(PART);
  private static final Pattern QUALIFIED = Pattern.compile(PART + "(\\." + PART + ")?");

  private final DatabaseEngine engine;
  private final Map<String, Variable> values = new HashMap<>();

  public HookVariables(DatabaseEngine engine) {
    this.engine = engine;
  }

  /**
   * Runs an {@code idb:set} query, which must return exactly one row, and stores each column under
   * its label. Returns the names set, upper-cased.
   */
  public List<String> set(Connection connection, String query) throws SQLException {
    Expanded expanded = expand(query);
    try (java.sql.Statement statement = statement(connection, expanded);
        ResultSet rows = executeQuery(statement, expanded)) {
      if (!rows.next()) {
        throw new InitiumDbException("idb:set query returned no row: " + query);
      }
      ResultSetMetaData meta = rows.getMetaData();
      Map<String, Variable> row = new HashMap<>();
      List<String> names = new ArrayList<>();
      for (int column = 1; column <= meta.getColumnCount(); column++) {
        String label = meta.getColumnLabel(column);
        if (label == null || !NAME.matcher(label).matches()) {
          throw new InitiumDbException(
              "idb:set column "
                  + column
                  + " is labeled '"
                  + label
                  + "', which is not a valid variable name; give it an alias such as"
                  + " max(id) AS max_id.");
        }
        String name = label.toUpperCase(Locale.ROOT);
        if (row.containsKey(name)) {
          throw new InitiumDbException("idb:set query sets variable " + name + " twice.");
        }
        row.put(
            name,
            new Variable(
                engine.hookValue(rows, column),
                meta.getColumnType(column),
                meta.getColumnTypeName(column)));
        names.add(name);
      }
      if (rows.next()) {
        throw new InitiumDbException("idb:set query returned more than one row: " + query);
      }
      values.putAll(row);
      return names;
    }
  }

  /**
   * A statement for {@code expanded}: a prepared one with its values bound when it has bind
   * variables, otherwise a plain one, so SQL without binds is sent exactly as before hook variables
   * existed (a {@code ?} operator, for example, is not taken as a placeholder). Run it with {@link
   * #execute} or {@link #executeQuery}.
   */
  public static java.sql.Statement statement(Connection connection, Expanded expanded)
      throws SQLException {
    try {
      return expanded.hasBinds() ? prepare(connection, expanded) : connection.createStatement();
    } catch (SQLException e) {
      throw expanded.hidingValues(e);
    }
  }

  /** Runs {@code expanded} on a statement from {@link #statement}. */
  public static void execute(java.sql.Statement statement, Expanded expanded) throws SQLException {
    try {
      if (statement instanceof PreparedStatement prepared && expanded.hasBinds()) {
        prepared.execute();
      } else {
        statement.execute(expanded.sql());
      }
    } catch (SQLException e) {
      throw expanded.hidingValues(e);
    }
  }

  /** Runs the query {@code expanded} on a statement from {@link #statement}. */
  public static ResultSet executeQuery(java.sql.Statement statement, Expanded expanded)
      throws SQLException {
    try {
      if (statement instanceof PreparedStatement prepared && expanded.hasBinds()) {
        return prepared.executeQuery();
      }
      return statement.executeQuery(expanded.sql());
    } catch (SQLException e) {
      throw expanded.hidingValues(e);
    }
  }

  private static PreparedStatement prepare(Connection connection, Expanded expanded)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(expanded.sql());
    try {
      for (int i = 0; i < expanded.binds().size(); i++) {
        Object value = expanded.binds().get(i);
        if (value == null) {
          statement.setNull(i + 1, expanded.bindTypes().get(i));
        } else {
          statement.setObject(i + 1, value);
        }
      }
      return statement;
    } catch (SQLException | RuntimeException e) {
      statement.close();
      throw e;
    }
  }

  /** Replaces the variable references of {@code sql}; see the class description. */
  public Expanded expand(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    List<Object> binds = new ArrayList<>();
    List<Integer> bindTypes = new ArrayList<>();
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '\'') {
        int end = closing(sql, i, '\'');
        out.append(sql, i, end);
        i = end;
      } else if (c == '"') {
        int end = closing(sql, i, '"');
        out.append(quotedIdentifier(sql.substring(i, end)));
        i = end;
      } else if (sql.startsWith("--", i)) {
        int end = sql.indexOf('\n', i);
        end = end < 0 ? n : end;
        out.append(sql, i, end);
        i = end;
      } else if (sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i + 2);
        end = end < 0 ? n : end + 2;
        out.append(sql, i, end);
        i = end;
      } else if (sql.startsWith("$${", i)) {
        int end = referenceEnd(sql, i + 3);
        out.append(identifier(sql.substring(i + 3, end - 1), QUALIFIED, "$${"));
        i = end;
      } else if (sql.startsWith("${", i)) {
        int end = referenceEnd(sql, i + 2);
        out.append(literal(sql.substring(i + 2, end - 1)));
        i = end;
      } else if (c == ':' && isBindStart(sql, i)) {
        int end = i + 1;
        while (end < n && isNamePart(sql.charAt(end))) {
          end++;
        }
        Variable variable = lookup(sql.substring(i + 1, end), ":");
        binds.add(variable.value());
        bindTypes.add(variable.jdbcType());
        out.append('?');
        i = end;
      } else {
        out.append(c);
        i++;
      }
    }
    return new Expanded(
        sql,
        out.toString(),
        Collections.unmodifiableList(binds),
        Collections.unmodifiableList(bindTypes));
  }

  /** Inside {@code "..."} only {@code $${name}} is replaced, by an unqualified identifier. */
  private String quotedIdentifier(String quoted) {
    StringBuilder out = new StringBuilder(quoted.length());
    int i = 0;
    while (i < quoted.length()) {
      if (quoted.startsWith("$${", i)) {
        int end = quoted.indexOf('}', i + 3);
        if (end < 0) {
          throw new InitiumDbException("Unterminated reference $${ in: " + quoted);
        }
        out.append(identifier(quoted.substring(i + 3, end), IDENTIFIER, "\"$${"));
        i = end + 1;
      } else {
        out.append(quoted.charAt(i++));
      }
    }
    return out.toString();
  }

  private String literal(String name) {
    Variable variable = lookup(name, "${");
    String upper = name.toUpperCase(Locale.ROOT);
    if (variable.value() == null) {
      throw new InitiumDbException(
          "Hook variable "
              + upper
              + " is NULL and cannot be written as a literal; bind it as :"
              + upper
              + " or set a default with coalesce in idb:set.");
    }
    return engine
        .literal(variable.value(), variable.jdbcType(), variable.typeName())
        .orElseThrow(
            () ->
                new InitiumDbException(
                    "Hook variable "
                        + upper
                        + " has type "
                        + variable.typeName()
                        + ", which cannot be written as a literal; bind it as :"
                        + upper
                        + "."));
  }

  private String identifier(String name, Pattern allowed, String form) {
    Variable variable = lookup(name, form);
    String upper = name.toUpperCase(Locale.ROOT);
    if (!(variable.value() instanceof String text) || !allowed.matcher(text).matches()) {
      throw new InitiumDbException(
          "Hook variable "
              + upper
              + " is not "
              + (allowed == QUALIFIED
                  ? "an identifier (optionally schema-qualified)"
                  : "an unqualified identifier")
              + ", so it cannot be written as "
              + form
              + upper
              + "}.");
    }
    return text;
  }

  private Variable lookup(String name, String form) {
    if (!NAME.matcher(name).matches()) {
      throw new InitiumDbException("Invalid hook variable reference " + form + name);
    }
    Variable variable = values.get(name.toUpperCase(Locale.ROOT));
    if (variable == null) {
      throw new InitiumDbException(
          "Hook variable "
              + name.toUpperCase(Locale.ROOT)
              + " is not defined; set it first with -- idb:set.");
    }
    return variable;
  }

  /** The index after the {@code }} that closes a {@code ${} or {@code $${} reference. */
  private static int referenceEnd(String sql, int start) {
    int end = sql.indexOf('}', start);
    if (end < 0) {
      throw new InitiumDbException("Unterminated variable reference in: " + sql);
    }
    return end + 1;
  }

  /** The index after a quoted section; a doubled quote is an escape. */
  private static int closing(String sql, int start, char quote) {
    int i = start + 1;
    while (i < sql.length()) {
      if (sql.charAt(i) == quote) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
          i += 2;
        } else {
          return i + 1;
        }
      } else {
        i++;
      }
    }
    return sql.length();
  }

  private static boolean isBindStart(String sql, int i) {
    if (i + 1 >= sql.length()) {
      return false;
    }
    char first = sql.charAt(i + 1);
    if (!isNamePart(first) || (first >= '0' && first <= '9')) {
      return false;
    }
    if (i == 0) {
      return true;
    }
    char before = sql.charAt(i - 1);
    return before != ':' && !isNamePart(before);
  }

  private static boolean isNamePart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
  }
}
