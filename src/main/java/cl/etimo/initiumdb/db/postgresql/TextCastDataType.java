package cl.etimo.initiumdb.db.postgresql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.datatype.AbstractDataType;

/**
 * A PostgreSQL column whose value is kept as PostgreSQL's own text form ({@code json}, {@code
 * jsonb}, arrays, enums, {@code interval}, {@code inet}, ...). It is read with {@code getString}
 * and bound as a parameter of unspecified type, so the server converts the text to the column's
 * type exactly as it would a literal. DBUnit has no type for most of these and leaves the column
 * out, or binds an enum as {@code varchar}, which PostgreSQL rejects.
 */
final class TextCastDataType extends AbstractDataType {

  static final TextCastDataType INSTANCE = new TextCastDataType();

  private TextCastDataType() {
    super("OTHER", Types.OTHER, String.class, false);
  }

  @Override
  public Object typeCast(Object value) {
    return value == null || value == ITable.NO_VALUE ? null : value.toString();
  }

  @Override
  public Object getSqlValue(int column, ResultSet resultSet) throws SQLException {
    return resultSet.getString(column);
  }

  @Override
  public void setSqlValue(Object value, int column, PreparedStatement statement)
      throws SQLException {
    statement.setObject(column, typeCast(value), Types.OTHER);
  }
}
