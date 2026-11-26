package cl.etimo.initiumdb.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.Columns;
import org.dbunit.dataset.DataSetException;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ITableIterator;
import org.dbunit.dataset.ITableMetaData;
import org.dbunit.dataset.NoPrimaryKeyException;
import org.dbunit.dataset.NoSuchColumnException;
import org.dbunit.dataset.RowOutOfBoundsException;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.operation.AbstractOperation;

/**
 * Faster replacement for DBUnit's {@code REFRESH} (update existing rows, insert missing ones).
 * DBUnit issues a {@code SELECT COUNT(*)} and then an {@code UPDATE} or {@code INSERT} for every
 * row, each in its own round trip. This one sends one upsert statement per row (the engine's own
 * syntax, see {@link #upsertSql}) but groups them in JDBC batches, so a round trip carries {@link
 * #BATCH_SIZE} rows.
 *
 * <p>Same inputs and rules as {@code REFRESH}: forward-only (streaming) datasets, keys taken from
 * the database, a table without a primary key is rejected. One difference: an attribute absent from
 * a row means {@code NULL} (Flat XML does not write nulls), so it overwrites the stored value;
 * {@code REFRESH} would leave it unchanged. Tables with columns of a type that is unsafe to bind in
 * an upsert (LOBs, engine-specific types), or that the engine cannot upsert safely (see {@link
 * #canUpsert}), are applied with DBUnit's own {@code REFRESH}.
 */
public abstract class BatchedUpsertOperation extends AbstractOperation {

  public static final int BATCH_SIZE = 500;

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
    List<Column> writable = new ArrayList<>();
    for (Column fileColumn : fileColumns) {
      Column column = Columns.getColumn(fileColumn.getColumnName(), dbMeta.getColumns());
      if (column == null) {
        throw new NoSuchColumnException(tableName, fileColumn.getColumnName());
      }
      if (!DbUnitRefreshOperation.isComputed(column)) {
        writable.add(column);
      }
    }
    Column[] columns = writable.toArray(new Column[0]);
    for (Column key : keys) {
      if (Columns.getColumn(key.getColumnName(), columns) == null) {
        throw new NoSuchColumnException(tableName, key.getColumnName() + " (primary key)");
      }
    }
    if (!allBatchable(columns) || !canUpsert(columns, dbMeta.getColumns())) {
      DbUnitRefreshOperation.refresh(connection, database, table);
      return;
    }
    String sql = upsertSql(connection, tableName, columns, keys);
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

  /**
   * Whether the engine's upsert is safe for a table whose dataset carries only {@code present} of
   * the table's {@code all} columns. When it is not, the table is applied with DBUnit's own {@code
   * REFRESH}. The default is always safe.
   */
  protected boolean canUpsert(Column[] present, Column[] all) {
    return true;
  }

  /**
   * Whether a column of this type is safe to bind in the engine's upsert. The default accepts the
   * standard scalar types; an engine may accept its own types.
   */
  protected boolean isBatchable(DataType type) {
    return BATCHABLE_TYPES.contains(type.getSqlType());
  }

  private boolean allBatchable(Column[] columns) {
    for (Column column : columns) {
      if (!isBatchable(column.getDataType())) {
        return false;
      }
    }
    return true;
  }

  private String upsertSql(
      IDatabaseConnection connection, String tableName, Column[] columns, Column[] keys)
      throws SQLException {
    List<String> all = new ArrayList<>();
    List<String> binds = new ArrayList<>();
    List<String> updatable = new ArrayList<>();
    List<String> keyNames = new ArrayList<>();
    for (Column column : columns) {
      String name = getQualifiedName(null, column.getColumnName(), connection);
      all.add(name);
      binds.add("?");
      if (Columns.getColumn(column.getColumnName(), keys) != null) {
        keyNames.add(name);
      } else {
        updatable.add(name);
      }
    }
    return upsertSql(
        getQualifiedName(connection.getSchema(), tableName, connection),
        all,
        binds,
        keyNames,
        updatable);
  }

  /**
   * Builds the statement that inserts one row or updates it when its key exists, with one bind per
   * column in {@code columns} order. All arguments are already quoted identifiers; {@code binds}
   * holds the placeholder of each column; {@code updatable} are the non-key columns (possibly
   * empty).
   */
  protected abstract String upsertSql(
      String table,
      List<String> columns,
      List<String> binds,
      List<String> keys,
      List<String> updatable);
}
