package cl.etimo.initiumdb.db.postgresql;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Set;
import org.dbunit.dataset.datatype.AbstractDataType;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.TypeCastException;

/**
 * PostgreSQL {@code numeric}, as DBUnit's {@link BigDecimal} type plus the values {@code NaN},
 * {@code Infinity} and {@code -Infinity} that {@code numeric} accepts and {@link BigDecimal} cannot
 * hold. Those are kept as text and bound as a parameter of unspecified type.
 */
final class NumericDataType extends AbstractDataType {

  static final NumericDataType INSTANCE = new NumericDataType();

  private static final Set<String> SPECIAL = Set.of("NaN", "Infinity", "-Infinity");

  private NumericDataType() {
    super("NUMERIC", Types.NUMERIC, BigDecimal.class, true);
  }

  @Override
  public Object typeCast(Object value) throws TypeCastException {
    if (value instanceof String text && SPECIAL.contains(text.strip())) {
      return text.strip();
    }
    return DataType.NUMERIC.typeCast(value);
  }

  @Override
  public Object getSqlValue(int column, ResultSet resultSet) throws SQLException {
    String text = resultSet.getString(column);
    if (text == null || SPECIAL.contains(text)) {
      return text;
    }
    return new BigDecimal(text);
  }

  @Override
  public void setSqlValue(Object value, int column, PreparedStatement statement)
      throws SQLException, TypeCastException {
    Object number = typeCast(value);
    if (number instanceof String text) {
      statement.setObject(column, text, Types.OTHER);
    } else {
      DataType.NUMERIC.setSqlValue(number, column, statement);
    }
  }
}
