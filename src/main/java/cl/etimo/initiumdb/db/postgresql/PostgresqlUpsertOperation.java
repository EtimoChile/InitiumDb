package cl.etimo.initiumdb.db.postgresql;

import cl.etimo.initiumdb.db.BatchedUpsertOperation;
import java.util.List;

/** PostgreSQL upsert: one {@code INSERT ... ON CONFLICT} per row, sent in JDBC batches. */
final class PostgresqlUpsertOperation extends BatchedUpsertOperation {

  @Override
  protected String upsertSql(
      String table,
      List<String> columns,
      List<String> binds,
      List<String> keys,
      List<String> updatable) {
    return insertOnConflictSql(table, columns, binds, keys, updatable);
  }

  /** Builds the upsert; arguments are already quoted identifiers. */
  static String insertOnConflictSql(
      String table,
      List<String> columns,
      List<String> binds,
      List<String> keys,
      List<String> updatable) {
    StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
    sql.append(String.join(", ", columns)).append(") VALUES (");
    sql.append(String.join(", ", binds)).append(") ON CONFLICT (");
    sql.append(String.join(", ", keys)).append(")");
    if (updatable.isEmpty()) {
      return sql.append(" DO NOTHING").toString();
    }
    sql.append(" DO UPDATE SET ");
    for (int i = 0; i < updatable.size(); i++) {
      sql.append(i == 0 ? "" : ", ")
          .append(updatable.get(i))
          .append(" = EXCLUDED.")
          .append(updatable.get(i));
    }
    return sql.toString();
  }
}
