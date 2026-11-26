package cl.etimo.initiumdb.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.NoPrimaryKeyException;
import org.dbunit.dataset.stream.StreamingDataSet;
import org.dbunit.dataset.xml.FlatXmlProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.InputSource;

/**
 * Contract: README.md > "idb-prepare" (Oracle and PostgreSQL apply the dataset with batched
 * upserts). Each engine's refresh operation is driven through the public engine contract against a
 * recording JDBC double (the metadata comes from an in-memory H2 database); what is asserted is
 * what reaches the driver: the statements, the bound values and the batches. The real statements
 * run against the real engines in the container integration tests.
 */
class UpsertContractTest {

  private Connection h2;
  private final List<String> statements = new ArrayList<>();
  private final List<List<Object>> rows = new ArrayList<>();
  private final List<Integer> batches = new ArrayList<>();
  private DatabaseConnection dbunit;

  @BeforeEach
  void setUp() throws Exception {
    h2 =
        DriverManager.getConnection(
            "jdbc:h2:mem:upsert"
                + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    try (Statement s = h2.createStatement()) {
      s.execute("CREATE TABLE ITEM (ID INT PRIMARY KEY, NAME VARCHAR(20), QTY INT)");
      s.execute("CREATE TABLE LINK (A INT, B INT, NOTE VARCHAR(20), PRIMARY KEY (A, B))");
      s.execute("CREATE TABLE ALL_KEY (ID INT PRIMARY KEY)");
      s.execute(
          "CREATE TABLE REQ (ID INT PRIMARY KEY, NAME VARCHAR(20) NOT NULL, STATUS VARCHAR(10))");
      s.execute(
          "CREATE TABLE DEFAULTED (ID INT PRIMARY KEY, NAME VARCHAR(20) DEFAULT 'd' NOT NULL,"
              + " STATUS VARCHAR(10))");
      s.execute("INSERT INTO REQ VALUES (1, 'Old', 'OLD')");
      s.execute("CREATE TABLE NO_KEY (ID INT, NAME VARCHAR(20))");
    }
    dbunit = new DatabaseConnection(recording(h2), "PUBLIC");
    dbunit.getConfig().setProperty(DatabaseConfig.PROPERTY_ESCAPE_PATTERN, "\"?\"");
  }

  @AfterEach
  void tearDown() throws SQLException {
    h2.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"oracle", "postgresql"})
  void anAttributeAbsentFromARowIsBoundAsNull(String engine) throws Exception {
    // When: Flat XML does not write nulls
    apply(engine, "<dataset><ITEM ID='1' NAME='x' QTY='7'/><ITEM ID='1' QTY='3'/></dataset>");
    // Then
    assertThat(rows).containsExactly(Arrays.asList(1, "x", 7), Arrays.asList(1, null, 3));
  }

  @ParameterizedTest
  @ValueSource(strings = {"oracle", "postgresql"})
  void aColumnThatAppearsAfterTheFirstRowIsKept(String engine) throws Exception {
    // When
    apply(engine, "<dataset><ITEM ID='3' NAME='a'/><ITEM ID='4' NAME='b' QTY='6'/></dataset>");
    // Then
    assertThat(statements).hasSize(1);
    assertThat(rows).containsExactly(Arrays.asList(3, "a", null), Arrays.asList(4, "b", 6));
  }

  @ParameterizedTest
  @ValueSource(strings = {"oracle", "postgresql"})
  void rowsAreSentInBatchesOfFiveHundred(String engine) throws Exception {
    // Given
    int total = BatchedUpsertOperation.BATCH_SIZE * 2 + 17;
    StringBuilder xml = new StringBuilder("<dataset>");
    for (int i = 0; i < total; i++) {
      xml.append("<ITEM ID='").append(i).append("' NAME='n").append(i).append("'/>");
    }
    // When
    apply(engine, xml.append("</dataset>").toString());
    // Then
    assertThat(statements).hasSize(1);
    assertThat(rows).hasSize(total);
    assertThat(batches).containsExactly(500, 500, 17);
  }

  @ParameterizedTest
  @ValueSource(strings = {"oracle", "postgresql"})
  void aTableWithoutPrimaryKeyIsRejected(String engine) {
    // When / Then
    assertThatThrownBy(() -> apply(engine, "<dataset><NO_KEY ID='1' NAME='a'/></dataset>"))
        .isInstanceOf(NoPrimaryKeyException.class);
    assertThat(statements).isEmpty();
  }

  @Test
  void oracleUpdatesNonKeyColumnsAndInsertsMissingRowsWithMerge() throws Exception {
    // When
    apply("oracle", "<dataset><ITEM ID='1' NAME='n' QTY='7'/></dataset>");
    // Then
    assertThat(statements)
        .containsExactly(
            "MERGE INTO \"PUBLIC\".\"ITEM\" t USING (SELECT ? \"ID\", ? \"NAME\", ? \"QTY\" FROM"
                + " dual) s ON (t.\"ID\" = s.\"ID\") WHEN MATCHED THEN UPDATE SET t.\"NAME\" ="
                + " s.\"NAME\", t.\"QTY\" = s.\"QTY\" WHEN NOT MATCHED THEN INSERT (\"ID\","
                + " \"NAME\", \"QTY\") VALUES (s.\"ID\", s.\"NAME\", s.\"QTY\")");
  }

  @Test
  void oracleMatchesCompositeKeysOnEveryKeyColumn() throws Exception {
    // When
    apply("oracle", "<dataset><LINK A='1' B='2' NOTE='n'/></dataset>");
    // Then
    assertThat(statements).hasSize(1);
    assertThat(statements.get(0))
        .contains("ON (t.\"A\" = s.\"A\" AND t.\"B\" = s.\"B\")")
        .contains("UPDATE SET t.\"NOTE\" = s.\"NOTE\"");
  }

  @Test
  void oracleOnlyInsertsWhenEveryColumnIsKey() throws Exception {
    // When
    apply("oracle", "<dataset><ALL_KEY ID='1'/></dataset>");
    // Then
    assertThat(statements).hasSize(1);
    assertThat(statements.get(0)).doesNotContain("WHEN MATCHED").contains("WHEN NOT MATCHED");
  }

  @Test
  void postgresqlUpdatesNonKeyColumnsOnConflict() throws Exception {
    // When
    apply("postgresql", "<dataset><ITEM ID='1' NAME='n' QTY='7'/></dataset>");
    // Then
    assertThat(statements)
        .containsExactly(
            "INSERT INTO \"PUBLIC\".\"ITEM\" (\"ID\", \"NAME\", \"QTY\") OVERRIDING SYSTEM VALUE"
                + " VALUES (?, ?, ?) ON"
                + " CONFLICT (\"ID\") DO UPDATE SET \"NAME\" = EXCLUDED.\"NAME\", \"QTY\" ="
                + " EXCLUDED.\"QTY\"");
  }

  @Test
  void postgresqlMatchesCompositeKeysOnEveryKeyColumn() throws Exception {
    // When
    apply("postgresql", "<dataset><LINK A='1' B='2' NOTE='n'/></dataset>");
    // Then
    assertThat(statements).hasSize(1);
    assertThat(statements.get(0))
        .contains("ON CONFLICT (\"A\", \"B\") DO UPDATE SET \"NOTE\" = EXCLUDED.\"NOTE\"");
  }

  @Test
  void postgresqlDoesNothingOnConflictWhenEveryColumnIsKey() throws Exception {
    // When
    apply("postgresql", "<dataset><ALL_KEY ID='1'/></dataset>");
    // Then
    assertThat(statements)
        .containsExactly(
            "INSERT INTO \"PUBLIC\".\"ALL_KEY\" (\"ID\") OVERRIDING SYSTEM VALUE VALUES (?) ON"
                + " CONFLICT (\"ID\") DO"
                + " NOTHING");
  }

  @Test
  void postgresqlUpdatesWithoutTheUpsertWhenANotNullColumnIsOmitted() throws Exception {
    // When: a partial capture (SELECT id, status) of a row that exists
    apply("postgresql", "<dataset><REQ ID='1' STATUS='NEW'/></dataset>");
    // Then: no INSERT ... ON CONFLICT is sent (it would fail), and the omitted column is kept
    assertThat(statements).isEmpty();
    assertThat(value("SELECT NAME || '/' || STATUS FROM REQ WHERE ID = 1")).isEqualTo("Old/NEW");
  }

  @Test
  void postgresqlStillUpsertsWhenTheOmittedNotNullColumnHasADefault() throws Exception {
    // When
    apply("postgresql", "<dataset><DEFAULTED ID='1' STATUS='S'/></dataset>");
    // Then
    assertThat(statements).hasSize(1);
    assertThat(statements.get(0)).contains("ON CONFLICT");
  }

  @Test
  void oracleKeepsTheMergeWhenANotNullColumnIsOmitted() throws Exception {
    // When: MERGE does not check NOT NULL for rows that match
    apply("oracle", "<dataset><REQ ID='1' STATUS='NEW'/></dataset>");
    // Then
    assertThat(statements).hasSize(1);
    assertThat(statements.get(0)).startsWith("MERGE INTO");
  }

  private String value(String query) throws SQLException {
    try (Statement s = h2.createStatement();
        java.sql.ResultSet rs = s.executeQuery(query)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private void apply(String engine, String xml) throws Exception {
    IDataSet dataSet =
        new StreamingDataSet(
            new FlatXmlProducer(new InputSource(new StringReader(xml)), false, true, true));
    Engines.named(engine).refreshOperation().execute(dbunit, dataSet);
  }

  /** A connection that records prepared statements and their batches instead of running them. */
  private Connection recording(Connection real) {
    return (Connection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("prepareStatement")
                  && args.length == 1
                  && (((String) args[0]).contains("ON CONFLICT")
                      || ((String) args[0]).startsWith("MERGE INTO"))) {
                statements.add((String) args[0]);
                return recordingStatement();
              }
              return delegate(real, method, args);
            });
  }

  private PreparedStatement recordingStatement() {
    Map<Integer, Object> bound = new TreeMap<>();
    int[] pending = {0};
    return (PreparedStatement)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.startsWith("set") && args != null && args[0] instanceof Integer index) {
                bound.put(index, name.equals("setNull") ? null : args[1]);
              } else if (name.equals("addBatch")) {
                rows.add(new ArrayList<>(bound.values()));
                pending[0]++;
              } else if (name.equals("executeBatch")) {
                batches.add(pending[0]);
                pending[0] = 0;
                return new int[0];
              }
              return null;
            });
  }

  private static Object delegate(Connection real, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(real, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
