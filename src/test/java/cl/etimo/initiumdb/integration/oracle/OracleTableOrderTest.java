package cl.etimo.initiumdb.integration.oracle;

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
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.oracle.OracleContainer;

/**
 * Contract: README.md > "Captured dataset" (table order and metadata requirements), against a
 * disposable Oracle. The dataset is applied in its declared table order, never reordered by
 * dependency, in one transaction, on both apply paths; foreign keys stay authoritative; rows are
 * matched by the primary key the database reports.
 */
@Tag("oracle_integration")
class OracleTableOrderTest {

  private static final OracleContainer ORACLE =
      new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
          // The image takes about two minutes to open its database on a laptop; the default is 60
          // s.
          .withStartupTimeout(Duration.ofMinutes(6));

  /** A login that owns nothing and holds only the grants InitiumDb needs on TDM_OWNER. */
  private static final String GRANTEE = "TDM_USER";

  private static final String GRANTEE_PASSWORD = "User#12345";

  private static final String[] TABLES = {
    "REGION",
    "CUSTOMER",
    "ACCOUNT",
    "NOTE",
    "EMPLOYEE",
    "PART_A",
    "PART_B",
    "ORDER_LINE",
    "AUDIT_LOG"
  };

  /** Every table, parents first; capture is only used by the grants-only test. */
  private static final String QUERIES =
      "schema: TDM_OWNER\n"
          + "tables:\n"
          + "  - name: REGION\n    sql: SELECT * FROM REGION ORDER BY ID\n"
          + "  - name: CUSTOMER\n    sql: SELECT * FROM CUSTOMER ORDER BY ID\n"
          + "  - name: NOTE\n    sql: SELECT * FROM NOTE ORDER BY ID\n"
          + "  - name: ACCOUNT\n    sql: SELECT * FROM ACCOUNT ORDER BY ID\n";

  @TempDir Path root;

  @BeforeAll
  static void startDatabase() throws SQLException {
    ORACLE.start();
    execute(
        "CREATE USER TDM_OWNER IDENTIFIED BY \"Owner#12345\" QUOTA UNLIMITED ON USERS",
        // three levels: REGION <- CUSTOMER <- ACCOUNT; NOTE has a BLOB (DBUnit REFRESH)
        "CREATE TABLE TDM_OWNER.REGION (ID NUMBER(10) PRIMARY KEY, NAME VARCHAR2(30) NOT NULL)",
        "CREATE TABLE TDM_OWNER.CUSTOMER (ID NUMBER(10) PRIMARY KEY, REGION_ID NUMBER(10) NOT"
            + " NULL REFERENCES TDM_OWNER.REGION (ID), NAME VARCHAR2(30) NOT NULL)",
        "CREATE TABLE TDM_OWNER.ACCOUNT (ID NUMBER(10) PRIMARY KEY, CUSTOMER_ID NUMBER(10) NOT"
            + " NULL REFERENCES TDM_OWNER.CUSTOMER (ID), BALANCE NUMBER(10))",
        "CREATE TABLE TDM_OWNER.NOTE (ID NUMBER(10) PRIMARY KEY, CUSTOMER_ID NUMBER(10) NOT NULL"
            + " REFERENCES TDM_OWNER.CUSTOMER (ID), BODY BLOB)",
        // a foreign key to the same table
        "CREATE TABLE TDM_OWNER.EMPLOYEE (ID NUMBER(10) PRIMARY KEY, MANAGER_ID NUMBER(10)"
            + " REFERENCES TDM_OWNER.EMPLOYEE (ID), NAME VARCHAR2(30))",
        // a cycle: PART_A.B_ID is optional, PART_B.A_ID is required
        "CREATE TABLE TDM_OWNER.PART_A (ID NUMBER(10) PRIMARY KEY, B_ID NUMBER(10))",
        "CREATE TABLE TDM_OWNER.PART_B (ID NUMBER(10) PRIMARY KEY, A_ID NUMBER(10) NOT NULL"
            + " REFERENCES TDM_OWNER.PART_A (ID))",
        "ALTER TABLE TDM_OWNER.PART_A ADD FOREIGN KEY (B_ID) REFERENCES TDM_OWNER.PART_B (ID)",
        "CREATE TABLE TDM_OWNER.ORDER_LINE (ORDER_ID NUMBER(10), LINE_NO NUMBER(10), QTY"
            + " NUMBER(10), PRIMARY KEY (ORDER_ID, LINE_NO))",
        "CREATE TABLE TDM_OWNER.AUDIT_LOG (MESSAGE VARCHAR2(30))",
        "CREATE USER " + GRANTEE + " IDENTIFIED BY \"" + GRANTEE_PASSWORD + "\"",
        "GRANT CREATE SESSION TO " + GRANTEE);
    for (String table : TABLES) {
      execute("GRANT SELECT, INSERT, UPDATE ON TDM_OWNER." + table + " TO " + GRANTEE);
    }
  }

  @AfterAll
  static void stopDatabase() {
    ORACLE.stop();
  }

  @BeforeEach
  void emptyTables() throws SQLException {
    execute(
        "UPDATE TDM_OWNER.PART_A SET B_ID = NULL",
        "DELETE FROM TDM_OWNER.PART_B",
        "DELETE FROM TDM_OWNER.PART_A",
        "UPDATE TDM_OWNER.EMPLOYEE SET MANAGER_ID = NULL",
        "DELETE FROM TDM_OWNER.EMPLOYEE",
        "DELETE FROM TDM_OWNER.ACCOUNT",
        "DELETE FROM TDM_OWNER.NOTE",
        "DELETE FROM TDM_OWNER.CUSTOMER",
        "DELETE FROM TDM_OWNER.REGION",
        "DELETE FROM TDM_OWNER.ORDER_LINE",
        "DELETE FROM TDM_OWNER.AUDIT_LOG");
  }

  /** The disposable instance's {@code system} account sets up and checks the data. */
  private static Connection connection() throws SQLException {
    return DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword());
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
    return scenario(dataset, "system", ORACLE.getPassword());
  }

  private EngineScenario scenario(String dataset, String user, String password) throws Exception {
    EngineScenario scenario =
        new EngineScenario(root, "oracle", ORACLE.getJdbcUrl(), user, password, QUERIES);
    Files.writeString(
        scenario.dataset(), "<dataset>\n" + dataset + "</dataset>\n", StandardCharsets.UTF_8);
    return scenario;
  }

  private static final String CHAIN =
      "<REGION ID=\"1\" NAME=\"North\"/>\n"
          + "<CUSTOMER ID=\"10\" REGION_ID=\"1\" NAME=\"Ana\"/>\n"
          + "<ACCOUNT ID=\"100\" CUSTOMER_ID=\"10\" BALANCE=\"5\"/>\n";

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
                "SELECT C.NAME || '/' || R.NAME || '/' || A.BALANCE FROM TDM_OWNER.ACCOUNT A"
                    + " JOIN TDM_OWNER.CUSTOMER C ON C.ID = A.CUSTOMER_ID"
                    + " JOIN TDM_OWNER.REGION R ON R.ID = C.REGION_ID"))
        .isEqualTo("Ana/North/5");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aChildDeclaredBeforeItsParentFailsAndAppliesNothing(String refresh) throws Exception {
    // Spec: README.md > Design principles (no reordering; database-enforced integrity) and
    // DEC-006 (the dataset is applied in one transaction)
    // Given: REGION is valid and comes first; ACCOUNT precedes the CUSTOMER it references
    EngineScenario scenario =
        scenario(
            "<REGION ID=\"1\" NAME=\"North\"/>\n"
                + "<ACCOUNT ID=\"100\" CUSTOMER_ID=\"10\" BALANCE=\"5\"/>\n"
                + "<CUSTOMER ID=\"10\" REGION_ID=\"1\" NAME=\"Ana\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    // The database's reason is reported; the batched MERGE does not name the table (PEND-019)
    assertThat(prepared.err()).contains("ORA-02291");
    assertThat(scalar("SELECT COUNT(*) FROM TDM_OWNER.REGION")).isEqualTo("0");
  }

  @Test
  void aTableAppliedWithDbunitRefreshKeepsItsPlaceAmongUpsertedTables() throws Exception {
    // Spec: README.md > idb-prepare (LOB tables fall back to DBUnit's REFRESH) and README.md >
    // Captured dataset (preserve dataset table order)
    // Given: NOTE (BLOB, REFRESH) between CUSTOMER and ACCOUNT (both merged)
    EngineScenario scenario =
        scenario(
            "<REGION ID=\"1\" NAME=\"North\"/>\n"
                + "<CUSTOMER ID=\"10\" REGION_ID=\"1\" NAME=\"Ana\"/>\n"
                + "<NOTE ID=\"50\" CUSTOMER_ID=\"10\" BODY=\"AQID\"/>\n"
                + "<ACCOUNT ID=\"100\" CUSTOMER_ID=\"10\" BALANCE=\"5\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT RAWTOHEX(DBMS_LOB.SUBSTR(BODY)) FROM TDM_OWNER.NOTE WHERE ID = 50"))
        .isEqualTo("010203");
    assertThat(scalar("SELECT BALANCE FROM TDM_OWNER.ACCOUNT WHERE ID = 100")).isEqualTo("5");
  }

  @Test
  void aFailureAfterARefreshedTableRollsBackEveryTable() throws Exception {
    // Spec: DEC-006 (one transaction) with DEC-011 (REFRESH fallback inside the same operation)
    // Given: NOTE is applied with REFRESH, then ACCOUNT references a missing customer
    EngineScenario scenario =
        scenario(
            "<REGION ID=\"1\" NAME=\"North\"/>\n"
                + "<CUSTOMER ID=\"10\" REGION_ID=\"1\" NAME=\"Ana\"/>\n"
                + "<NOTE ID=\"50\" CUSTOMER_ID=\"10\" BODY=\"AQID\"/>\n"
                + "<ACCOUNT ID=\"100\" CUSTOMER_ID=\"99\" BALANCE=\"5\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(scalar("SELECT COUNT(*) FROM TDM_OWNER.NOTE")).isEqualTo("0");
    assertThat(scalar("SELECT COUNT(*) FROM TDM_OWNER.REGION")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void rowsOfASelfReferencingTableAreAppliedInDatasetOrder(String refresh) throws Exception {
    // Spec: README.md > Design principles (table rows are processed in the order specified)
    // Given
    EngineScenario scenario =
        scenario(
            "<EMPLOYEE ID=\"1\" NAME=\"Boss\"/>\n"
                + "<EMPLOYEE ID=\"2\" MANAGER_ID=\"1\" NAME=\"Lead\"/>\n"
                + "<EMPLOYEE ID=\"3\" MANAGER_ID=\"2\" NAME=\"Dev\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar(
                "SELECT M.NAME FROM TDM_OWNER.EMPLOYEE E JOIN TDM_OWNER.EMPLOYEE M"
                    + " ON M.ID = E.MANAGER_ID WHERE E.ID = 3"))
        .isEqualTo("Lead");
  }

  @Test
  void aSelfReferencingRowBeforeItsManagerFails() throws Exception {
    // Spec: README.md > Design principles (no reordering; database-enforced integrity)
    // Given
    EngineScenario scenario =
        scenario(
            "<EMPLOYEE ID=\"2\" MANAGER_ID=\"1\" NAME=\"Lead\"/>\n"
                + "<EMPLOYEE ID=\"1\" NAME=\"Boss\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare();

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(scalar("SELECT COUNT(*) FROM TDM_OWNER.EMPLOYEE")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aCycleIsAppliedByDeclaringATableTwice(String refresh) throws Exception {
    // Spec: README.md > Design principles (the case author controls the order of dataset tables)
    // Given: PART_A without its reference, then PART_B, then PART_A again with the reference
    EngineScenario scenario =
        scenario(
            "<PART_A ID=\"1\"/>\n"
                + "<PART_B ID=\"7\" A_ID=\"1\"/>\n"
                + "<PART_A ID=\"1\" B_ID=\"7\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT B_ID FROM TDM_OWNER.PART_A WHERE ID = 1")).isEqualTo("7");
    assertThat(scalar("SELECT A_ID FROM TDM_OWNER.PART_B WHERE ID = 7")).isEqualTo("1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void rowsAreMatchedByEveryColumnOfACompositePrimaryKey(String refresh) throws Exception {
    // Spec: README.md > idb-prepare (rows are matched by the primary key read from the database)
    // Given: (1, 1) exists; the dataset updates it and adds (1, 2)
    execute(
        "INSERT INTO TDM_OWNER.ORDER_LINE VALUES (1, 1, 5)",
        "INSERT INTO TDM_OWNER.ORDER_LINE VALUES (2, 1, 9)");
    EngineScenario scenario =
        scenario(
            "<ORDER_LINE ORDER_ID=\"1\" LINE_NO=\"1\" QTY=\"7\"/>\n"
                + "<ORDER_LINE ORDER_ID=\"1\" LINE_NO=\"2\" QTY=\"3\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(
            scalar(
                "SELECT LISTAGG(ORDER_ID || ':' || LINE_NO || '=' || QTY, ',') WITHIN GROUP"
                    + " (ORDER BY ORDER_ID, LINE_NO) FROM TDM_OWNER.ORDER_LINE"))
        .isEqualTo("1:1=7,1:2=3,2:1=9");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aTableWithoutAPrimaryKeyIsRejectedNamingItAndAppliesNothing(String refresh)
      throws Exception {
    // Spec: README.md > idb-prepare (a table without a primary key is rejected)
    // Given
    EngineScenario scenario =
        scenario("<REGION ID=\"1\" NAME=\"North\"/>\n<AUDIT_LOG MESSAGE=\"x\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.err()).contains("AUDIT_LOG");
    assertThat(scalar("SELECT COUNT(*) FROM TDM_OWNER.REGION")).isEqualTo("0");
    assertThat(scalar("SELECT COUNT(*) FROM TDM_OWNER.AUDIT_LOG")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aTableMissingFromTheSchemaIsRejectedNamingIt(String refresh) throws Exception {
    // Spec: README.md > SQL identifiers and quoting (DBUnit resolves names against the metadata)
    // Given
    EngineScenario scenario =
        scenario("<REGION ID=\"1\" NAME=\"North\"/>\n<NO_SUCH_TABLE ID=\"1\"/>\n");

    // When
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(prepared.exitCode()).isEqualTo(1);
    assertThat(prepared.err()).contains("NO_SUCH_TABLE");
    assertThat(scalar("SELECT COUNT(*) FROM TDM_OWNER.REGION")).isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"optimized", "dbunit"})
  void aUserWithOnlyTableGrantsCapturesAndPreparesAnotherOwnersSchema(String refresh)
      throws Exception {
    // Spec: README.md > Directory layout (the example's TDM user is not the schema owner) and
    // README.md > Captured dataset (metadata requirements)
    // Given: rows owned by TDM_OWNER; InitiumDb connects as a grants-only user
    execute(
        "INSERT INTO TDM_OWNER.REGION VALUES (1, 'North')",
        "INSERT INTO TDM_OWNER.CUSTOMER VALUES (10, 1, 'Ana')",
        "INSERT INTO TDM_OWNER.NOTE VALUES (50, 10, HEXTORAW('0102'))",
        "INSERT INTO TDM_OWNER.ACCOUNT VALUES (100, 10, 5)");
    EngineScenario scenario = scenario("", GRANTEE, GRANTEE_PASSWORD);
    Files.delete(scenario.dataset());

    // When
    EngineScenario.Result captured = scenario.capture();
    execute("UPDATE TDM_OWNER.ACCOUNT SET BALANCE = 0", "UPDATE TDM_OWNER.NOTE SET BODY = NULL");
    EngineScenario.Result prepared = scenario.prepare(refresh);

    // Then
    assertThat(captured.exitCode()).as(captured.err()).isZero();
    assertThat(prepared.exitCode()).as(prepared.err()).isZero();
    assertThat(scalar("SELECT BALANCE FROM TDM_OWNER.ACCOUNT WHERE ID = 100")).isEqualTo("5");
    assertThat(scalar("SELECT RAWTOHEX(DBMS_LOB.SUBSTR(BODY)) FROM TDM_OWNER.NOTE WHERE ID = 50"))
        .isEqualTo("0102");
  }
}
