package cl.etimo.initiumdb.integration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.integration.EngineScenario;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contract: README.md > "Captured dataset" (table order and metadata requirements), against a
 * disposable PostgreSQL. The dataset is applied in its declared table order, never reordered by
 * dependency, in one transaction, on both apply paths; foreign keys stay authoritative; rows are
 * matched by the primary key the database reports.
 */
@Tag("postgresql_integration")
class PostgresqlTableOrderTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  /** A login that owns nothing and holds only the grants InitiumDb needs on {@code tdm}. */
  private static final String GRANTEE = "tdm_user";

  private static final String GRANTEE_PASSWORD = "User#12345";

  /** Every table, parents first; capture is only used by the grants-only test. */
  private static final String QUERIES =
      "schema: tdm\n"
          + "tables:\n"
          + "  - name: region\n    sql: SELECT * FROM region ORDER BY id\n"
          + "  - name: customer\n    sql: SELECT * FROM customer ORDER BY id\n"
          + "  - name: note\n    sql: SELECT * FROM note ORDER BY id\n"
          + "  - name: account\n    sql: SELECT * FROM account ORDER BY id\n";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    POSTGRES.start();
    execute(
        "CREATE SCHEMA tdm",
        // three levels: region <- customer <- account; note has a bytea column (DBUnit REFRESH)
        "CREATE TABLE tdm.region (id INT PRIMARY KEY, name VARCHAR(30) NOT NULL)",
        "CREATE TABLE tdm.customer (id INT PRIMARY KEY, region_id INT NOT NULL REFERENCES"
            + " tdm.region (id), name VARCHAR(30) NOT NULL)",
        "CREATE TABLE tdm.account (id INT PRIMARY KEY, customer_id INT NOT NULL REFERENCES"
            + " tdm.customer (id), balance INT)",
        "CREATE TABLE tdm.note (id INT PRIMARY KEY, customer_id INT NOT NULL REFERENCES"
            + " tdm.customer (id), body BYTEA)",
        // a foreign key to the same table
        "CREATE TABLE tdm.employee (id INT PRIMARY KEY, manager_id INT REFERENCES tdm.employee"
            + " (id), name VARCHAR(30))",
        // a cycle: part_a.b_id is optional, part_b.a_id is required
        "CREATE TABLE tdm.part_a (id INT PRIMARY KEY, b_id INT)",
        "CREATE TABLE tdm.part_b (id INT PRIMARY KEY, a_id INT NOT NULL REFERENCES tdm.part_a"
            + " (id))",
        "ALTER TABLE tdm.part_a ADD FOREIGN KEY (b_id) REFERENCES tdm.part_b (id)",
        "CREATE TABLE tdm.order_line (order_id INT, line_no INT, qty INT,"
            + " PRIMARY KEY (order_id, line_no))",
        "CREATE TABLE tdm.audit_log (message VARCHAR(30))",
        "CREATE ROLE " + GRANTEE + " LOGIN PASSWORD '" + GRANTEE_PASSWORD + "'",
        "GRANT USAGE ON SCHEMA tdm TO " + GRANTEE,
        "GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA tdm TO " + GRANTEE);
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void emptyTables() throws SQLException {
    execute(
        "UPDATE tdm.part_a SET b_id = NULL",
        "DELETE FROM tdm.part_b",
        "DELETE FROM tdm.part_a",
        "UPDATE tdm.employee SET manager_id = NULL",
        "DELETE FROM tdm.employee",
        "DELETE FROM tdm.account",
        "DELETE FROM tdm.note",
        "DELETE FROM tdm.customer",
        "DELETE FROM tdm.region",
        "DELETE FROM tdm.order_line",
        "DELETE FROM tdm.audit_log");
  }

  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void execute(String... statements) throws SQLException {
    try (Connection c = connection();
        Statement s = c.createStatement()) {
      for (String statement : statements) {
        s.execute(statement);
      }
    }
  }

  private static String scalar(String query) throws SQLException {
    try (Connection c = connection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(query)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private EngineScenario scenario(String dataset) throws Exception {
    return scenario(dataset, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private EngineScenario scenario(String dataset, String user, String password) throws Exception {
    EngineScenario scenario =
        new EngineScenario(root, "postgresql", POSTGRES.getJdbcUrl(), user, password, QUERIES);
    Files.writeString(
        scenario.dataset(), "<dataset>\n" + dataset + "</dataset>\n", StandardCharsets.UTF_8);
    return scenario;
  }

  private static final String CHAIN =
      "<region id=\"1\" name=\"North\"/>\n"
          + "<customer id=\"10\" region_id=\"1\" name=\"Ana\"/>\n"
          + "<account id=\"100\" customer_id=\"10\" balance=\"5\"/>\n";

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aThreeLevelChainDeclaredParentsFirstIsApplied(String refresh) throws Exception {
    // Spec: README.md > Design principles (respect declared order)
    // Given
    EngineScenario scenario = scenario(CHAIN);

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar(
                "SELECT c.name || '/' || r.name || '/' || a.balance FROM tdm.account a"
                    + " JOIN tdm.customer c ON c.id = a.customer_id JOIN tdm.region r ON r.id ="
                    + " c.region_id"))
        .isEqualTo("Ana/North/5");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aChildDeclaredBeforeItsParentFailsAndAppliesNothing(String refresh) throws Exception {
    // Spec: README.md > Design principles (no reordering; database-enforced integrity) and
    // DEC-006 (the dataset is applied in one transaction)
    // Given: region is valid and comes first; account precedes the customer it references
    EngineScenario scenario =
        scenario(
            "<region id=\"1\" name=\"North\"/>\n"
                + "<account id=\"100\" customer_id=\"10\" balance=\"5\"/>\n"
                + "<customer id=\"10\" region_id=\"1\" name=\"Ana\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.err()).contains("account");
    assertThat(scalar("SELECT count(*) FROM tdm.region")).isEqualTo("0");
  }

  @Test
  void aTableAppliedWithDbunitRefreshKeepsItsPlaceAmongUpsertedTables() throws Exception {
    // Spec: README.md > idb-prepare (LOB tables fall back to DBUnit's REFRESH) and README.md >
    // Captured dataset (preserve dataset table order)
    // Given: note (bytea, REFRESH) between customer and account (both upserted)
    EngineScenario scenario =
        scenario(
            "<region id=\"1\" name=\"North\"/>\n"
                + "<customer id=\"10\" region_id=\"1\" name=\"Ana\"/>\n"
                + "<note id=\"50\" customer_id=\"10\" body=\"AQID\"/>\n"
                + "<account id=\"100\" customer_id=\"10\" balance=\"5\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT encode(body, 'hex') FROM tdm.note WHERE id = 50"))
        .isEqualTo("010203");
    assertThat(scalar("SELECT balance FROM tdm.account WHERE id = 100")).isEqualTo("5");
  }

  @Test
  void aFailureAfterARefreshedTableRollsBackEveryTable() throws Exception {
    // Spec: DEC-006 (one transaction) with DEC-011 (REFRESH fallback inside the same operation)
    // Given: note is applied with REFRESH, then account references a missing customer
    EngineScenario scenario =
        scenario(
            "<region id=\"1\" name=\"North\"/>\n"
                + "<customer id=\"10\" region_id=\"1\" name=\"Ana\"/>\n"
                + "<note id=\"50\" customer_id=\"10\" body=\"AQID\"/>\n"
                + "<account id=\"100\" customer_id=\"99\" balance=\"5\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(scalar("SELECT count(*) FROM tdm.note")).isEqualTo("0");
    assertThat(scalar("SELECT count(*) FROM tdm.region")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void rowsOfASelfReferencingTableAreAppliedInDatasetOrder(String refresh) throws Exception {
    // Spec: README.md > Design principles (table rows are processed in the order specified)
    // Given
    EngineScenario scenario =
        scenario(
            "<employee id=\"1\" name=\"Boss\"/>\n"
                + "<employee id=\"2\" manager_id=\"1\" name=\"Lead\"/>\n"
                + "<employee id=\"3\" manager_id=\"2\" name=\"Dev\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar(
                "SELECT m.name FROM tdm.employee e JOIN tdm.employee m ON m.id ="
                    + " e.manager_id WHERE e.id = 3"))
        .isEqualTo("Lead");
  }

  @Test
  void aSelfReferencingRowBeforeItsManagerFails() throws Exception {
    // Spec: README.md > Design principles (no reordering; database-enforced integrity)
    // Given
    EngineScenario scenario =
        scenario(
            "<employee id=\"2\" manager_id=\"1\" name=\"Lead\"/>\n"
                + "<employee id=\"1\" name=\"Boss\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(scalar("SELECT count(*) FROM tdm.employee")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aCycleIsAppliedByDeclaringATableTwice(String refresh) throws Exception {
    // Spec: README.md > Design principles (the case author controls the order of dataset tables)
    // Given: part_a without its reference, then part_b, then part_a again with the reference
    EngineScenario scenario =
        scenario(
            "<part_a id=\"1\"/>\n"
                + "<part_b id=\"7\" a_id=\"1\"/>\n"
                + "<part_a id=\"1\" b_id=\"7\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT b_id FROM tdm.part_a WHERE id = 1")).isEqualTo("7");
    assertThat(scalar("SELECT a_id FROM tdm.part_b WHERE id = 7")).isEqualTo("1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void rowsAreMatchedByEveryColumnOfACompositePrimaryKey(String refresh) throws Exception {
    // Spec: README.md > idb-prepare (rows are matched by the primary key read from the database)
    // Given: (1, 1) exists; the dataset updates it and adds (1, 2)
    execute("INSERT INTO tdm.order_line VALUES (1, 1, 5), (2, 1, 9)");
    EngineScenario scenario =
        scenario(
            "<order_line order_id=\"1\" line_no=\"1\" qty=\"7\"/>\n"
                + "<order_line order_id=\"1\" line_no=\"2\" qty=\"3\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar(
                "SELECT string_agg(order_id || ':' || line_no || '=' || qty, ',' ORDER BY"
                    + " order_id, line_no) FROM tdm.order_line"))
        .isEqualTo("1:1=7,1:2=3,2:1=9");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aTableWithoutAPrimaryKeyIsRejectedNamingItAndAppliesNothing(String refresh)
      throws Exception {
    // Spec: README.md > idb-prepare (a table without a primary key is rejected)
    // Given
    EngineScenario scenario =
        scenario("<region id=\"1\" name=\"North\"/>\n<audit_log message=\"x\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.err()).contains("audit_log");
    assertThat(scalar("SELECT count(*) FROM tdm.region")).isEqualTo("0");
    assertThat(scalar("SELECT count(*) FROM tdm.audit_log")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aTableMissingFromTheSchemaIsRejectedNamingIt(String refresh) throws Exception {
    // Spec: README.md > SQL identifiers and quoting (DBUnit resolves names against the metadata)
    // Given
    EngineScenario scenario =
        scenario("<region id=\"1\" name=\"North\"/>\n<no_such_table id=\"1\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.err()).contains("no_such_table");
    assertThat(scalar("SELECT count(*) FROM tdm.region")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aUserWithOnlyTableGrantsCapturesAndPreparesAnotherOwnersSchema(String refresh)
      throws Exception {
    // Spec: README.md > Directory layout (the example's TDM user is not the schema owner) and
    // README.md > Captured dataset (metadata requirements)
    // Given: rows owned by the container user; InitiumDb connects as a grants-only user
    execute(
        "INSERT INTO tdm.region VALUES (1, 'North')",
        "INSERT INTO tdm.customer VALUES (10, 1, 'Ana')",
        "INSERT INTO tdm.note VALUES (50, 10, '\\x0102'::bytea)",
        "INSERT INTO tdm.account VALUES (100, 10, 5)");
    EngineScenario scenario = scenario("", GRANTEE, GRANTEE_PASSWORD);
    Files.delete(scenario.dataset());

    // When
    EngineScenario.Result captured = scenario.capture();
    execute("UPDATE tdm.account SET balance = 0", "UPDATE tdm.note SET body = NULL");
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT balance FROM tdm.account WHERE id = 100")).isEqualTo("5");
    assertThat(scalar("SELECT encode(body, 'hex') FROM tdm.note WHERE id = 50")).isEqualTo("0102");
  }
}
