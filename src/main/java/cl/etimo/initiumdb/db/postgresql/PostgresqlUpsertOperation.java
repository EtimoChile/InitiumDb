package cl.etimo.initiumdb.db.postgresql;

import cl.etimo.initiumdb.db.BatchedUpsertOperation;
import java.util.List;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.Columns;
import org.dbunit.dataset.datatype.DataType;

/** PostgreSQL upsert: one {@code INSERT ... ON CONFLICT} per row, sent in JDBC batches. */
final class PostgresqlUpsertOperation extends BatchedUpsertOperation {

  /** Columns kept as PostgreSQL text are bound as untyped parameters, which an upsert accepts. */
  @Override
  protected boolean isBatchable(DataType type) {
    return type instanceof TextCastDataType || super.isBatchable(type);
  }

  /**
   * {@code INSERT ... ON CONFLICT} checks NOT NULL on the proposed row before it looks for the
   * conflict, so a dataset that omits a NOT NULL column without a default fails even when every row
   * already exists (a partial {@code SELECT col1, col2} capture). DBUnit's {@code REFRESH} updates
   * only the present columns, so those tables use it.
   */
  @Override
  protected boolean canUpsert(Column[] present, Column[] all) {
    for (Column column : all) {
      boolean omitted = Columns.getColumn(column.getColumnName(), present) == null;
      boolean generated =
          column.hasDefaultValue()
              || column.getAutoIncrement() == Column.AutoIncrement.YES
              || Boolean.TRUE.equals(column.getGeneratedColumn());
      if (omitted && column.isNotNullable() && !generated) {
        return false;
      }
    }
    return true;
  }

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
  private static String insertOnConflictSql(
      String table,
      List<String> columns,
      List<String> binds,
      List<String> keys,
      List<String> updatable) {
    StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
    // An identity column GENERATED ALWAYS accepts the captured value only with this clause; it
    // changes nothing for other columns.
    sql.append(String.join(", ", columns)).append(") OVERRIDING SYSTEM VALUE VALUES (");
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
