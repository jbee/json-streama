package se.jbee.json.stream;

import java.util.function.Function;

public interface JsonMapping {

  static JsonMapping auto() {
    return AutoJsonMapper.SHARED;
  }

  static <A, B> Function<A, B> unsupported(Class<A> from, Class<B> to) {
    return json -> {
      throw new UnsupportedOperationException(
          String.format(
              "Unknown conversion from value `%s` (%s) to %s",
              json, from.getSimpleName(), to.getSimpleName()));
    };
  }

  static Number toNumber(String value) {
    double number = Double.parseDouble(value);
    if (number % 1 == 0d) {
      long asLong = (long) number;
      if (asLong < Integer.MAX_VALUE && asLong > Integer.MIN_VALUE) {
        return (int) asLong;
      } else return asLong;
    } else return number;
  }

  <T> JsonTo<T> mapTo(Class<T> to);

  record JsonTo<T>(
      Class<T> to,
      Function<String, T> mapString,
      Function<Number, T> mapNumber,
      Function<Boolean, T> mapBoolean) {}
}
