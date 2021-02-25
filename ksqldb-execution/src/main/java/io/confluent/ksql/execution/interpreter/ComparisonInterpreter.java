/*
 * Copyright 2021 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.interpreter;

import io.confluent.ksql.execution.expression.tree.ComparisonExpression;
import io.confluent.ksql.execution.interpreter.CastInterpreter.ConversionType;
import io.confluent.ksql.execution.interpreter.CastInterpreter.NumberConversions;
import io.confluent.ksql.execution.interpreter.terms.BasicTerms.BooleanTerm;
import io.confluent.ksql.execution.interpreter.terms.ComparisonTerm.CompareToTerm;
import io.confluent.ksql.execution.interpreter.terms.ComparisonTerm.ComparisonFunction;
import io.confluent.ksql.execution.interpreter.terms.ComparisonTerm.ComparisonNullCheckFunction;
import io.confluent.ksql.execution.interpreter.terms.ComparisonTerm.EqualsFunction;
import io.confluent.ksql.execution.interpreter.terms.ComparisonTerm.EqualsTerm;
import io.confluent.ksql.execution.interpreter.terms.Term;
import io.confluent.ksql.schema.ksql.SqlTimestamps;
import io.confluent.ksql.schema.ksql.types.SqlBaseType;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.Pair;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Contains lots of the logic for doing comparisons in the interpreter.
 */
public final class ComparisonInterpreter {

  private ComparisonInterpreter() { }

  /**
   * After a comparison has been done between two expressions of comparable types, this
   * evaluates the result according to the requested operation.
   * @param type The comparison type
   * @return The evaluated result
   */
  public static BooleanTerm doComparisonCheck(
      final ComparisonExpression.Type type,
      final Term left,
      final Term right,
      final ComparisonNullCheckFunction nullCheckFunction,
      final ComparisonFunction comparisonFunction
  ) {
    switch (type) {
      case EQUAL:
        return new CompareToTerm(left, right, nullCheckFunction, comparisonFunction,
            compareTo -> compareTo == 0);
      case NOT_EQUAL:
      case IS_DISTINCT_FROM:
        return new CompareToTerm(left, right, nullCheckFunction, comparisonFunction,
            compareTo -> compareTo != 0);
      case GREATER_THAN_OR_EQUAL:
        return new CompareToTerm(left, right, nullCheckFunction, comparisonFunction,
            compareTo -> compareTo >= 0);
      case GREATER_THAN:
        return new CompareToTerm(left, right, nullCheckFunction, comparisonFunction,
            compareTo -> compareTo > 0);
      case LESS_THAN_OR_EQUAL:
        return new CompareToTerm(left, right, nullCheckFunction, comparisonFunction,
            compareTo -> compareTo <= 0);
      case LESS_THAN:
        return new CompareToTerm(left, right, nullCheckFunction, comparisonFunction,
            compareTo -> compareTo < 0);
      default:
        throw new KsqlException(String.format("Unsupported comparison between %s and %s: %s",
            left.getSqlType(), right.getSqlType(), type));
    }
  }

  public static BooleanTerm doEqualsCheck(
      final ComparisonExpression.Type type,
      final Term left,
      final Term right,
      final ComparisonNullCheckFunction nullCheckFunction,
      final EqualsFunction equalsFunction
  ) {
    switch (type) {
      case EQUAL:
        return new EqualsTerm(left, right, nullCheckFunction, equalsFunction, equals -> equals);
      case NOT_EQUAL:
      case IS_DISTINCT_FROM:
        return new EqualsTerm(left, right, nullCheckFunction, equalsFunction, equals -> !equals);
      default:
        throw new KsqlException(String.format("Unsupported comparison between %s and %s: %s",
            left.getSqlType(), left.getSqlType(), type));
    }
  }

  public static BigDecimal toDecimal(final Object object, final SqlType from,
      final ConversionType type) {
    if (object instanceof BigDecimal) {
      return (BigDecimal) object;
    } else if (object instanceof Double) {
      return BigDecimal.valueOf((Double) object);
    } else if (object instanceof Integer) {
      return new BigDecimal((Integer) object);
    } else if (object instanceof Long) {
      return new BigDecimal((Long) object);
    } else if (object instanceof String) {
      return new BigDecimal((String) object);
    } else {
      throw new KsqlException(String.format("Unsupported comparison between %s and %s", from,
          SqlBaseType.DECIMAL));
    }
  }

  public static Timestamp toTimestamp(final Object object, final SqlType from,
      final ConversionType type) {
    if (object instanceof Timestamp) {
      return (Timestamp) object;
    } else if (object instanceof String) {
      return SqlTimestamps.parseTimestamp((String) object);
    } else {
      throw new KsqlException(String.format("Unsupported comparison between %s and %s", from,
          SqlTypes.TIMESTAMP));
    }
  }

  public static ComparisonNullCheckFunction doNullCheck(final ComparisonExpression.Type type) {
    if (type == ComparisonExpression.Type.IS_DISTINCT_FROM) {
      return (c, l, r) -> {
        final Object leftObject = l.getValue(c);
        final Object rightObject = r.getValue(c);
        if (leftObject == null || rightObject == null) {
          return Optional.of((leftObject == null) ^ (rightObject == null));
        }
        return Optional.empty();
      };
    }
    return (c, l, r) -> {
      if (l.getValue(c) == null || r.getValue(c) == null) {
        return Optional.of(false);
      }
      return Optional.empty();
    };
  }

  public static Optional<ComparisonFunction> doCompareTo(
      final Pair<Term, SqlType> left,
      final Pair<Term, SqlType> right) {
    final SqlBaseType leftType = left.getRight().baseType();
    final SqlBaseType rightType = right.getRight().baseType();
    if (either(leftType, rightType, SqlBaseType.DECIMAL)) {
      return Optional.of((c, l, r) -> doCompareTo(c, ComparisonInterpreter::toDecimal, l, r));
    } else if (either(leftType, rightType, SqlBaseType.TIMESTAMP)) {
      return Optional.of((c, l, r) -> doCompareTo(c, ComparisonInterpreter::toTimestamp, l, r));
    } else if (leftType == SqlBaseType.STRING) {
      return Optional.of((c, l, r) -> l.getValue(c).toString().compareTo(r.getValue(c).toString()));
    } else if (either(leftType, rightType, SqlBaseType.DOUBLE)) {
      return Optional.of((c, l, r) -> doCompareTo(c, NumberConversions::toDouble, l, r));
    } else if (either(leftType, rightType, SqlBaseType.BIGINT)) {
      return Optional.of((c, l, r) -> doCompareTo(c, NumberConversions::toLong, l, r));
    } else if (either(leftType, rightType, SqlBaseType.INTEGER)) {
      return Optional.of((c, l, r) -> doCompareTo(c, NumberConversions::toInteger, l, r));
    }
    return Optional.empty();
  }

  public static <T extends Comparable<T>> int doCompareTo(
      final TermEvaluationContext context,
      final Conversion<T> conversion,
      final Term left,
      final Term right) {
    final Object leftObject = left.getValue(context);
    final Object rightObject = right.getValue(context);
    return conversion.convert(leftObject, left.getSqlType(), ConversionType.COMPARISON).compareTo(
        conversion.convert(rightObject, right.getSqlType(), ConversionType.COMPARISON));
  }

  private static boolean either(
      final SqlBaseType leftType,
      final SqlBaseType rightType,
      final SqlBaseType value) {
    return leftType == value || rightType == value;
  }

  public static Optional<EqualsFunction> doEquals(
      final Pair<Term, SqlType> left,
      final Pair<Term, SqlType> right
  ) {
    final SqlBaseType leftType = left.getRight().baseType();

    switch (leftType) {
      case ARRAY:
      case MAP:
      case STRUCT:
      case BOOLEAN:
        return Optional.of((c, l, r) -> l.getValue(c).equals(r.getValue(c)));
      default:
        return Optional.empty();
    }
  }

  public interface Conversion<T extends Comparable> {
    T convert(Object object, SqlType from, ConversionType type);
  }
}
