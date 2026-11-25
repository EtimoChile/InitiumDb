package cl.etimo.initiumdb.db.oracle;

import cl.etimo.initiumdb.db.BatchedUpsertOperation;
import java.util.List;
import java.util.function.Function;
import org.dbunit.dataset.Column;

/** Oracle upsert: one {@code MERGE} per row, sent in JDBC batches. */
final class OracleMergeOperation extends BatchedUpsertOperation {

  OracleMergeOperation() {
    super();
  }

  /** Tests may cast the bind, because not every engine infers the type of a bare {@code ?}. */
  OracleMergeOperation(Function<Column, String> placeholder) {
    super(placeholder);
  }

  @Override
  protected String upsertSql(
      String table,
      List<String> columns,
      List<String> binds,
      List<String> keys,
      List<String> updatable) {
    return mergeSql(table, columns, binds, keys, updatable);
  }

  /** Builds the {@code MERGE}; arguments are already quoted identifiers. */
  static String mergeSql(
      String table,
      List<String> columns,
      List<String> binds,
      List<String> keys,
      List<String> updatable) {
    StringBuilder sql = new StringBuilder("MERGE INTO ").append(table).append(" t USING (SELECT ");
    for (int i = 0; i < columns.size(); i++) {
      sql.append(i == 0 ? "" : ", ").append(binds.get(i)).append(" ").append(columns.get(i));
    }
    sql.append(" FROM dual) s ON (");
    for (int i = 0; i < keys.size(); i++) {
      sql.append(i == 0 ? "" : " AND ")
          .append("t.")
          .append(keys.get(i))
          .append(" = s.")
          .append(keys.get(i));
    }
    sql.append(")");
    if (!updatable.isEmpty()) {
      sql.append(" WHEN MATCHED THEN UPDATE SET ");
      for (int i = 0; i < updatable.size(); i++) {
        sql.append(i == 0 ? "" : ", ")
            .append("t.")
            .append(updatable.get(i))
            .append(" = s.")
            .append(updatable.get(i));
      }
    }
    sql.append(" WHEN NOT MATCHED THEN INSERT (").append(String.join(", ", columns));
    sql.append(") VALUES (");
    for (int i = 0; i < columns.size(); i++) {
      sql.append(i == 0 ? "" : ", ").append("s.").append(columns.get(i));
    }
    return sql.append(")").toString();
  }
}
