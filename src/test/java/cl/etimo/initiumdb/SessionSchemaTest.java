package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cl.etimo.initiumdb.db.DatabaseEngine;
import cl.etimo.initiumdb.db.Engines;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract: README.md > "SQL identifiers and quoting". */
class SessionSchemaTest {

  /** A JDBC double that records every executed statement. */
  private static Connection recordingConnection(List<String> executed) throws SQLException {
    Statement statement = mock(Statement.class);
    when(statement.execute(anyString()))
        .thenAnswer(
            invocation -> {
              executed.add(invocation.getArgument(0));
              return true;
            });
    // PostgreSQL checks that the schema exists, so the double lists the ones the tests use
    java.util.Iterator<String> names = List.of("Crm Owner", "x\"; DROP TABLE t; --").iterator();
    ResultSet schemas = mock(ResultSet.class);
    when(schemas.next()).thenAnswer(invocation -> names.hasNext());
    when(schemas.getString("TABLE_SCHEM")).thenAnswer(invocation -> names.next());
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(metaData.getSchemas()).thenReturn(schemas);
    Connection connection = mock(Connection.class);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.getMetaData()).thenReturn(metaData);
    return connection;
  }

  private static List<String> useSchema(String engine, String schema) throws SQLException {
    List<String> executed = new ArrayList<>();
    DatabaseEngine target = Engines.named(engine);
    target.useSchema(recordingConnection(executed), schema);
    return executed;
  }

  @Test
  void theSchemaKeepsTheExactCaseWrittenInTheConfiguration() throws SQLException {
    // Spec: README.md > "SQL identifiers and quoting"
    // Given / When / Then
    assertThat(useSchema("oracle", "Crm_Owner"))
        .containsExactly("ALTER SESSION SET CURRENT_SCHEMA = \"Crm_Owner\"");
    assertThat(useSchema("postgresql", "Crm Owner"))
        .containsExactly("SET search_path TO \"Crm Owner\"");
  }

  @Test
  void aSchemaCannotInjectStatements() throws SQLException {
    // Spec: README.md > "SQL identifiers and quoting"
    // Given / When / Then
    assertThat(useSchema("postgresql", "x\"; DROP TABLE t; --"))
        .containsExactly("SET search_path TO \"x\"\"; DROP TABLE t; --\"");
  }

  @Test
  void aPostgresqlSchemaThatDoesNotExistIsRejectedWithItsName() throws SQLException {
    // Spec: README.md > "SQL identifiers and quoting" (names are case-sensitive)
    // Given / When / Then
    List<String> executed = new ArrayList<>();
    DatabaseEngine target = Engines.named("postgresql");
    Connection connection = recordingConnection(executed);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> target.useSchema(connection, "crm owner"))
        .hasMessageContaining("Schema 'crm owner' does not exist");
    assertThat(executed).isEmpty();
  }

  @Test
  void aBlankSchemaLeavesTheSessionUnchanged() throws SQLException {
    // Spec: README.md > "SQL identifiers and quoting"
    // Given / When / Then
    assertThat(useSchema("oracle", " ")).isEmpty();
    assertThat(useSchema("postgresql", null)).isEmpty();
  }
}
