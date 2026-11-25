package cl.etimo.initiumdb.core;

import java.util.regex.Pattern;

/**
 * Rule for logical names (database aliases and credential names): upper-case letters, digits and
 * {@code _}, starting with a letter. The rule keeps lookups exact and makes the matching
 * environment variable names ({@code IDB_<ALIAS>_URL}, {@code IDB_CREDENTIAL_<NAME>}) the name
 * itself, so no two names can collide.
 */
public final class Names {

  private static final Pattern VALID = Pattern.compile("[A-Z][A-Z0-9_]*");

  private Names() {}

  public static boolean isValid(String name) {
    return name != null && VALID.matcher(name).matches();
  }

  /** Fails unless {@code name} follows the rule; {@code where} names the file it came from. */
  public static void require(String name, String what, String where) {
    if (!isValid(name)) {
      throw new InitiumDbException(
          "Invalid "
              + what
              + " '"
              + name
              + "'"
              + (where == null ? "" : " in " + where)
              + ": use upper-case letters, digits and '_', starting with a letter.");
    }
  }
}
