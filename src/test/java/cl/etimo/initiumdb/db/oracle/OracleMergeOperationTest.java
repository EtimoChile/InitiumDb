package cl.etimo.initiumdb.db.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
import org.xml.sax.InputSource;

/** Contract: README.md > "idb-prepare" (Oracle applies the dataset with batched MERGE). */
class OracleMergeOperationTest {

  Connection connection;
  DatabaseConnection dbunit;

  @BeforeEach
  void setUp() throws Exception {
    connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:merge"
                + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1",
            "sa",
            "");
    sql(
        "CREATE TABLE ITEM (ID INT PRIMARY KEY, NAME VARCHAR(20), QTY INT)",
        "CREATE TABLE LINK (A INT, B INT, NOTE VARCHAR(20), PRIMARY KEY (A, B))",
        "CREATE TABLE ALL_KEY (ID INT PRIMARY KEY)",
        "CREATE TABLE NO_KEY (ID INT, NAME VARCHAR(20))",
        "INSERT INTO ITEM VALUES (1, 'old', 5)");
    dbunit = new DatabaseConnection(connection, "PUBLIC");
    dbunit.getConfig().setProperty(DatabaseConfig.PROPERTY_ESCAPE_PATTERN, "\"?\"");
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void mergeSqlUpdatesNonKeyColumnsAndInsertsMissingRows() {
    // When
    String sql =
        OracleMergeOperation.mergeSql(
            "\"T\"",
            List.of("\"ID\"", "\"NAME\""),
            List.of("?", "?"),
            List.of("\"ID\""),
            List.of("\"NAME\""));
    // Then
    assertThat(sql)
        .isEqualTo(
            "MERGE INTO \"T\" t USING (SELECT ? \"ID\", ? \"NAME\" FROM dual) s ON (t.\"ID\" ="
                + " s.\"ID\") WHEN MATCHED THEN UPDATE SET t.\"NAME\" = s.\"NAME\" WHEN NOT MATCHED"
                + " THEN INSERT (\"ID\", \"NAME\") VALUES (s.\"ID\", s.\"NAME\")");
  }

  @Test
  void mergeSqlOmitsTheUpdateWhenEveryColumnIsKey() {
    // When
    String sql =
        OracleMergeOperation.mergeSql(
            "\"T\"", List.of("\"ID\""), List.of("?"), List.of("\"ID\""), List.of());
    // Then
    assertThat(sql).doesNotContain("WHEN MATCHED").contains("WHEN NOT MATCHED");
  }

  @Test
  void updatesExistingRowsInsertsNewOnesAndLeavesOthersAlone() throws Exception {
    // Given
    sql("INSERT INTO ITEM VALUES (9, 'other', 1)");
    // When
    apply("<dataset><ITEM ID='1' NAME='new' QTY='7'/><ITEM ID='2' NAME='two' QTY='8'/></dataset>");
    // Then
    assertThat(value("SELECT NAME || '/' || QTY FROM ITEM WHERE ID = 1")).isEqualTo("new/7");
    assertThat(value("SELECT NAME || '/' || QTY FROM ITEM WHERE ID = 2")).isEqualTo("two/8");
    assertThat(value("SELECT NAME FROM ITEM WHERE ID = 9")).isEqualTo("other");
  }

  @Test
  void anAttributeAbsentFromARowBecomesNull() throws Exception {
    // When: the second row has no NAME (Flat XML does not write nulls)
    apply("<dataset><ITEM ID='1' NAME='x' QTY='7'/><ITEM ID='1' QTY='3'/></dataset>");
    // Then
    assertThat(value("SELECT NAME FROM ITEM WHERE ID = 1")).isNull();
    assertThat(value("SELECT QTY FROM ITEM WHERE ID = 1")).isEqualTo("3");
  }

  @Test
  void aColumnThatAppearsAfterTheFirstRowIsKept() throws Exception {
    // When
    apply("<dataset><ITEM ID='3' NAME='a'/><ITEM ID='4' NAME='b' QTY='6'/></dataset>");
    // Then
    assertThat(value("SELECT QTY FROM ITEM WHERE ID = 4")).isEqualTo("6");
    assertThat(value("SELECT QTY FROM ITEM WHERE ID = 3")).isNull();
  }

  @Test
  void compositeKeysMatchOnAllKeyColumns() throws Exception {
    // Given
    sql("INSERT INTO LINK VALUES (1, 1, 'old')");
    // When
    apply("<dataset><LINK A='1' B='1' NOTE='new'/><LINK A='1' B='2' NOTE='added'/></dataset>");
    // Then
    assertThat(value("SELECT COUNT(*) FROM LINK")).isEqualTo("2");
    assertThat(value("SELECT NOTE FROM LINK WHERE A = 1 AND B = 1")).isEqualTo("new");
  }

  @Test
  void aTableWhereEveryColumnIsKeyIsInsertedOnce() throws Exception {
    // When
    apply("<dataset><ALL_KEY ID='1'/><ALL_KEY ID='1'/><ALL_KEY ID='2'/></dataset>");
    // Then
    assertThat(value("SELECT COUNT(*) FROM ALL_KEY")).isEqualTo("2");
  }

  @Test
  void moreRowsThanOneBatchAreAllApplied() throws Exception {
    // Given
    int rows = OracleMergeOperation.BATCH_SIZE * 2 + 17;
    StringBuilder xml = new StringBuilder("<dataset>");
    for (int i = 100; i < 100 + rows; i++) {
      xml.append("<ITEM ID='").append(i).append("' NAME='n").append(i).append("'/>");
    }
    // When
    apply(xml.append("</dataset>").toString());
    // Then
    assertThat(value("SELECT COUNT(*) FROM ITEM")).isEqualTo(String.valueOf(rows + 1));
  }

  @Test
  void aTableWithoutPrimaryKeyIsRejected() {
    // When / Then
    assertThatThrownBy(() -> apply("<dataset><NO_KEY ID='1' NAME='a'/></dataset>"))
        .isInstanceOf(NoPrimaryKeyException.class);
  }

  private void apply(String xml) throws Exception {
    IDataSet dataSet =
        new StreamingDataSet(
            new FlatXmlProducer(new InputSource(new StringReader(xml)), false, true, true));
    // H2 cannot infer the type of a bare ? in a select list (Oracle can), so the test casts it
    new OracleMergeOperation(
            c ->
                "CAST(? AS "
                    + (c.getDataType().getSqlType() == java.sql.Types.INTEGER
                        ? "INTEGER"
                        : "VARCHAR(100)")
                    + ")")
        .execute(dbunit, dataSet);
  }

  private void sql(String... statements) throws SQLException {
    try (Statement s = connection.createStatement()) {
      for (String statement : statements) {
        s.execute(statement);
      }
    }
  }

  private String value(String query) throws SQLException {
    try (Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery(query)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }
}
