package cl.etimo.initiumdb.db.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract: README.md > "idb-prepare" (PostgreSQL applies the dataset with batched upserts). */
class PostgresqlUpsertOperationTest {

  @Test
  void upsertUpdatesNonKeyColumnsOnConflict() {
    // When
    String sql =
        PostgresqlUpsertOperation.insertOnConflictSql(
            "\"T\"",
            List.of("\"ID\"", "\"NAME\"", "\"QTY\""),
            List.of("?", "?", "?"),
            List.of("\"ID\""),
            List.of("\"NAME\"", "\"QTY\""));
    // Then
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"T\" (\"ID\", \"NAME\", \"QTY\") VALUES (?, ?, ?) ON CONFLICT (\"ID\")"
                + " DO UPDATE SET \"NAME\" = EXCLUDED.\"NAME\", \"QTY\" = EXCLUDED.\"QTY\"");
  }

  @Test
  void upsertDoesNothingOnConflictWhenEveryColumnIsKey() {
    // When
    String sql =
        PostgresqlUpsertOperation.insertOnConflictSql(
            "\"T\"",
            List.of("\"A\"", "\"B\""),
            List.of("?", "?"),
            List.of("\"A\"", "\"B\""),
            List.of());
    // Then
    assertThat(sql)
        .isEqualTo(
            "INSERT INTO \"T\" (\"A\", \"B\") VALUES (?, ?) ON CONFLICT (\"A\", \"B\") DO NOTHING");
  }
}
