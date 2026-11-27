package cl.etimo.initiumdb.db;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.datatype.AbstractDataType;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.TypeCastException;

/**
 * A binary floating-point column (PostgreSQL {@code double precision} and {@code real}, Oracle
 * {@code BINARY_DOUBLE} and {@code BINARY_FLOAT}) read and bound as {@code double} or {@code
 * float}. DBUnit's own types parse the dataset text through {@link BigDecimal}, which cannot hold
 * {@code NaN} or the infinities and turns {@code -0.0} into {@code 0}.
 *
 * <p>The dataset text is Java's ({@link Double#toString}, {@link Float#toString}), as DBUnit wrote
 * it: the shortest decimal that parses back to the same bits, plus {@code NaN}, {@code Infinity}
 * and {@code -Infinity}.
 */
public final class FloatingPointDataType extends AbstractDataType {

  /** Oracle's {@code BINARY_DOUBLE} and {@code BINARY_FLOAT} type codes ({@code OracleTypes}). */
  public static final int ORACLE_BINARY_DOUBLE = 101;

  public static final int ORACLE_BINARY_FLOAT = 100;

  private final boolean single;
  private final Integer bindType;

  private FloatingPointDataType(boolean single, Integer bindType) {
    super(
        single ? "REAL" : "DOUBLE",
        single ? Types.REAL : Types.DOUBLE,
        single ? Float.class : Double.class,
        true);
    this.single = single;
    this.bindType = bindType;
  }

  /** A {@code double} column bound with the driver's {@code setDouble}. */
  public static FloatingPointDataType doubles() {
    return new FloatingPointDataType(false, null);
  }

  /** A {@code float} column bound with the driver's {@code setFloat}. */
  public static FloatingPointDataType floats() {
    return new FloatingPointDataType(true, null);
  }

  /**
   * A column bound with {@code setObject(column, value, bindType)}: Oracle's driver converts a
   * value bound with {@code setDouble} to {@code NUMBER}, which overflows past 1E126 and has no
   * {@code NaN}.
   */
  public static FloatingPointDataType boundAs(boolean single, int bindType) {
    return new FloatingPointDataType(single, bindType);
  }

  @Override
  public Object typeCast(Object value) throws TypeCastException {
    if (value == null || value == ITable.NO_VALUE) {
      return null;
    }
    if (value instanceof Number number && !(value instanceof BigDecimal)) {
      return single ? (Object) number.floatValue() : (Object) number.doubleValue();
    }
    String text = value.toString().strip();
    try {
      return single ? (Object) Float.parseFloat(text) : (Object) Double.parseDouble(text);
    } catch (NumberFormatException e) {
      throw new TypeCastException(value, this, e);
    }
  }

  @Override
  public Object getSqlValue(int column, ResultSet resultSet) throws SQLException {
    Object value =
        single ? (Object) resultSet.getFloat(column) : (Object) resultSet.getDouble(column);
    return resultSet.wasNull() ? null : value;
  }

  @Override
  public void setSqlValue(Object value, int column, PreparedStatement statement)
      throws SQLException, TypeCastException {
    Object number = typeCast(value);
    if (number == null) {
      statement.setNull(column, getSqlType());
    } else if (bindType != null) {
      statement.setObject(column, number, bindType);
    } else if (single) {
      statement.setFloat(column, (Float) number);
    } else {
      statement.setDouble(column, (Double) number);
    }
  }

  /**
   * Replaces DBUnit's {@code DOUBLE}, {@code FLOAT} and {@code REAL} types; any other type is
   * returned unchanged.
   */
  public static DataType replacing(DataType created) {
    if (created == DataType.DOUBLE || created == DataType.FLOAT) {
      return doubles();
    }
    if (created == DataType.REAL) {
      return floats();
    }
    return created;
  }
}
