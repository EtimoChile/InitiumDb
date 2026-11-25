package cl.etimo.initiumdb.db.oracle;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.Columns;
import org.dbunit.dataset.DataSetException;
import org.dbunit.dataset.DefaultDataSet;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ITableIterator;
import org.dbunit.dataset.ITableMetaData;
import org.dbunit.dataset.NoPrimaryKeyException;
import org.dbunit.dataset.NoSuchColumnException;
import org.dbunit.dataset.RowOutOfBoundsException;
import org.dbunit.operation.AbstractOperation;
import org.dbunit.operation.DatabaseOperation;

/**
 * Oracle replacement for DBUnit's {@code REFRESH} (update existing rows, insert missing ones).
 * DBUnit's operation issues a {@code SELECT COUNT(*)} and then an {@code UPDATE} or {@code INSERT}
 * for every row, each in its own round trip. This one sends a {@code MERGE} per row but groups them
 * in JDBC batches, so a round trip carries {@link #BATCH_SIZE} rows.
 *
 * <p>Same inputs and rules as {@code REFRESH}: forward-only (streaming) datasets, keys taken from
 * the database, a table without a primary key is rejected. One difference: an attribute absent from
 * a row means {@code NULL} (Flat XML does not write nulls), so it overwrites the stored value;
 * {@code REFRESH} would leave it unchanged. Tables with columns of a type that is unsafe to bind in
 * a {@code MERGE} source (LOBs, engine-specific types) are applied with DBUnit's own {@code
 * REFRESH}.
 */
final class OracleMergeOperation extends AbstractOperation {

  static final int BATCH_SIZE = 500;

  private final Function<Column, String> placeholder;

  OracleMergeOperation() {
    this(column -> "?");
  }

  /** Tests may cast the bind, because not every engine infers the type of a bare {@code ?}. */
  OracleMergeOperation(Function<Column, String> placeholder) {
    this.placeholder = placeholder;
  }

  private static final Set<Integer> BATCHABLE_TYPES =
      Set.of(
          Types.CHAR,
          Types.VARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.NUMERIC,
          Types.DECIMAL,
          Types.INTEGER,
          Types.BIGINT,
          Types.SMALLINT,
          Types.TINYINT,
          Types.FLOAT,
          Types.REAL,
          Types.DOUBLE,
          Types.DATE,
          Types.TIME,
          Types.TIMESTAMP,
          Types.BIT,
          Types.BOOLEAN);

  @Override
  public void execute(IDatabaseConnection connection, IDataSet dataSet)
      throws DatabaseUnitException, SQLException {
    IDataSet database = connection.createDataSet();
    ITableIterator tables = dataSet.iterator();
    while (tables.next()) {
      mergeTable(connection, database, tables.getTable());
    }
  }

  private void mergeTable(IDatabaseConnection connection, IDataSet database, ITable table)
      throws DatabaseUnitException, SQLException {
    ITableMetaData fileMeta = table.getTableMetaData();
    String tableName = fileMeta.getTableName();
    Column[] fileColumns = fileMeta.getColumns();
    if (fileColumns.length == 0) {
      return;
    }
    ITableMetaData dbMeta = database.getTableMetaData(tableName);
    Column[] keys = dbMeta.getPrimaryKeys();
    if (keys.length == 0) {
      throw new NoPrimaryKeyException(tableName);
    }
    Column[] columns = new Column[fileColumns.length];
    for (int i = 0; i < columns.length; i++) {
      columns[i] = Columns.getColumn(fileColumns[i].getColumnName(), dbMeta.getColumns());
      if (columns[i] == null) {
        throw new NoSuchColumnException(tableName, fileColumns[i].getColumnName());
      }
    }
    for (Column key : keys) {
      if (Columns.getColumn(key.getColumnName(), columns) == null) {
        throw new NoSuchColumnException(tableName, key.getColumnName() + " (primary key)");
      }
    }
    if (!allBatchable(columns)) {
      DatabaseOperation.REFRESH.execute(connection, new DefaultDataSet(table));
      return;
    }
    String sql = mergeSql(connection, tableName, columns, keys);
    try (PreparedStatement statement = connection.getConnection().prepareStatement(sql)) {
      writeRows(table, columns, statement);
    }
  }

  private static void writeRows(ITable table, Column[] columns, PreparedStatement statement)
      throws DataSetException, SQLException {
    int pending = 0;
    for (int row = 0; ; row++) {
      Object[] values = new Object[columns.length];
      try {
        for (int i = 0; i < columns.length; i++) {
          values[i] = table.getValue(row, columns[i].getColumnName());
        }
      } catch (RowOutOfBoundsException end) {
        break;
      }
      for (int i = 0; i < columns.length; i++) {
        Object value = values[i] == ITable.NO_VALUE ? null : values[i];
        if (value == null) {
          statement.setNull(i + 1, columns[i].getDataType().getSqlType());
        } else {
          columns[i].getDataType().setSqlValue(value, i + 1, statement);
        }
      }
      statement.addBatch();
      if (++pending == BATCH_SIZE) {
        statement.executeBatch();
        pending = 0;
      }
    }
    if (pending > 0) {
      statement.executeBatch();
    }
  }

  private static boolean allBatchable(Column[] columns) {
    for (Column column : columns) {
      if (!BATCHABLE_TYPES.contains(column.getDataType().getSqlType())) {
        return false;
      }
    }
    return true;
  }

  private String mergeSql(
      IDatabaseConnection connection, String tableName, Column[] columns, Column[] keys)
      throws SQLException {
    List<String> all = new ArrayList<>();
    List<String> binds = new ArrayList<>();
    List<String> updatable = new ArrayList<>();
    List<String> keyNames = new ArrayList<>();
    for (Column column : columns) {
      String name = getQualifiedName(null, column.getColumnName(), connection);
      all.add(name);
      binds.add(placeholder.apply(column));
      if (Columns.getColumn(column.getColumnName(), keys) != null) {
        keyNames.add(name);
      } else {
        updatable.add(name);
      }
    }
    return mergeSql(
        getQualifiedName(connection.getSchema(), tableName, connection),
        all,
        binds,
        keyNames,
        updatable);
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
