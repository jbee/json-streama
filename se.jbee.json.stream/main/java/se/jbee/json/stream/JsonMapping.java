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
              "Unknown conversion from %s (%s) to %s",
              json, from.getSimpleName(), to.getSimpleName()));
    };
  }

  <T> JsonTo<T> mapTo(Class<T> to);

  record JsonTo<T>(
      Class<T> to,
      Function<String, T> mapString,
      Function<Number, T> mapNumber,
      Function<Boolean, T> mapBoolean) {}
}
