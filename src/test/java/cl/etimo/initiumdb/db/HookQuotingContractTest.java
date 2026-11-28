package cl.etimo.initiumdb.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract: README.md > "Hook variables" (nothing is replaced inside string literals or comments):
 * the engine-specific quoted sections a hook statement is scanned past. The integration tests run
 * the same statements against the real engines.
 */
class HookQuotingContractTest {

  private final DatabaseEngine oracle = Engines.named("oracle");
  private final DatabaseEngine postgresql = Engines.named("postgresql");

  /** The quoted section starting at the first {@code marker} of {@code sql}, or empty. */
  private static String section(DatabaseEngine engine, String sql, String marker) {
    int start = sql.indexOf(marker);
    int end = engine.quotedSectionEnd(sql, start);
    return end < 0 ? "" : sql.substring(start, end);
  }

  @Test
  void postgresqlEscapeStringsEndAtAnUnescapedQuote() {
    String sql = "SELECT E'it\\'s :x' || e'a''b' , :y";
    assertThat(section(postgresql, sql, "E'")).isEqualTo("E'it\\'s :x'");
    assertThat(section(postgresql, sql, "e'")).isEqualTo("e'a''b'");
    // the end of a longer name is not an escape string
    assertThat(section(postgresql, "SELECT name'x'", "e'")).isEmpty();
  }

  @Test
  void postgresqlDollarQuotesEndAtTheSameTag() {
    String sql = "SELECT $$a $q$ :x$$, $q$b $$ :x$q$, :y";
    assertThat(section(postgresql, sql, "$$")).isEqualTo("$$a $q$ :x$$");
    assertThat(section(postgresql, sql, "$q$b")).isEqualTo("$q$b $$ :x$q$");
  }

  @Test
  void postgresqlVariableReferencesAndParametersAreNotDollarQuotes() {
    assertThat(section(postgresql, "SELECT $${seq} || $$x$$", "$${")).isEmpty();
    assertThat(section(postgresql, "SELECT ${n}", "${")).isEmpty();
    assertThat(section(postgresql, "SELECT $1", "$1")).isEmpty();
    assertThat(section(postgresql, "SELECT a$b$ FROM t", "$b$")).isEmpty();
  }

  @Test
  void postgresqlBlockCommentsNest() {
    String sql = "SELECT 1 /* outer /* inner */ :x */ , :y";
    assertThat(section(postgresql, sql, "/*")).isEqualTo("/* outer /* inner */ :x */");
  }

  @Test
  void oracleAlternativeQuotesEndAtTheirDelimiterAndAQuote() {
    String sql = "SELECT q'[it's :x]' || NQ'{a]' :x}' || q'!b'!' || Q'(c)' FROM DUAL, :y";
    assertThat(section(oracle, sql, "q'[")).isEqualTo("q'[it's :x]'");
    assertThat(section(oracle, sql, "NQ'")).isEqualTo("NQ'{a]' :x}'");
    assertThat(section(oracle, sql, "q'!")).isEqualTo("q'!b'!'");
    assertThat(section(oracle, sql, "Q'(")).isEqualTo("Q'(c)'");
    // the end of a longer name is not a quote
    assertThat(section(oracle, "SELECT seq'x' FROM DUAL", "q'")).isEmpty();
  }

  @Test
  void eachEngineSkipsOnlyItsOwnQuotedSections() {
    assertThat(section(oracle, "SELECT E'x' FROM DUAL", "E'")).isEmpty();
    assertThat(section(oracle, "SELECT $$x$$ FROM DUAL", "$$")).isEmpty();
    assertThat(section(postgresql, "SELECT q'[x]'", "q'")).isEmpty();
  }
}
