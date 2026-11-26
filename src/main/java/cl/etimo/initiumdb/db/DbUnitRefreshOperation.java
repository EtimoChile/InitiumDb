package cl.etimo.initiumdb.db;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.operation.AbstractOperation;
import org.dbunit.operation.DatabaseOperation;

/**
 * DBUnit's {@code REFRESH}, table by table, leaving out the columns the database computes itself
 * (PostgreSQL {@code GENERATED ALWAYS AS (...) STORED}, Oracle virtual columns). A capture of the
 * whole table includes them, but the database rejects any value written to them and computes them
 * again from the other columns.
 */
public final class DbUnitRefreshOperation extends AbstractOperation {

  @Override
  public void execute(IDatabaseConnection connection, IDataSet dataSet)
      throws DatabaseUnitException, SQLException {
    IDataSet database = connection.createDataSet();
    ITableIterator tables = dataSet.iterator();
    while (tables.next()) {
      refresh(connection, database, tables.getTable());
    }
  }

  /** Applies one table with DBUnit's {@code REFRESH}, without its computed columns. */
  static void refresh(IDatabaseConnection connection, IDataSet database, ITable table)
      throws DatabaseUnitException, SQLException {
    ITableMetaData dbMeta = database.getTableMetaData(table.getTableMetaData().getTableName());
    Column[] computed = computedColumns(table.getTableMetaData().getColumns(), dbMeta);
    ITable writable =
        computed.length == 0 ? table : DefaultColumnFilter.excludedColumnsTable(table, computed);
    DatabaseOperation.REFRESH.execute(connection, new DefaultDataSet(writable));
  }

  /** The dataset columns that the database computes, as columns of {@code dbMeta}. */
  static Column[] computedColumns(Column[] datasetColumns, ITableMetaData dbMeta)
      throws DataSetException {
    List<Column> computed = new ArrayList<>();
    for (Column column : datasetColumns) {
      Column inDatabase = Columns.getColumn(column.getColumnName(), dbMeta.getColumns());
      if (isComputed(inDatabase)) {
        computed.add(inDatabase);
      }
    }
    return computed.toArray(new Column[0]);
  }

  static boolean isComputed(Column column) {
    return column != null && Boolean.TRUE.equals(column.getGeneratedColumn());
  }
}
