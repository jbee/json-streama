package se.jbee.json.stream;

import java.util.function.Function;

@FunctionalInterface
public interface JsonToJava {

  static JsonToJava auto() {
    return JsonToJavaRegistry.JVM_SHARED;
  }

  static <A, B> Function<A, B> unsupported(Class<A> from, Class<B> to) {
    return json -> {
      throw new UnsupportedOperationException(
          String.format(
              "Unknown conversion from value `%s` (%s) to %s",
              json, from.getSimpleName(), to.getSimpleName()));
    };
  }

  <T> JsonTo<T> mapTo(Class<T> to);

  /**
   * Conversion of any simple JSON value to a specific Java type.
   *
   * <p>Usually a target Java type is a simple value type but this is also used for collections and
   * streaming types, mostly to get the adequate {@code null} value.
   *
   * @param to target Java type
   * @param mapNull "immutable" constant to use for JSON null and undefined members
   * @param mapString converts a JSON string to the target Java type
   * @param mapNumber converts a JSON number to the target Java type
   * @param mapBoolean converts a JSON boolean to the target Java type
   * @param <T> the target Java type
   */
  record JsonTo<T>(
      Class<T> to,
      T mapNull,
      Function<String, ? extends T> mapString,
      Function<Number, ? extends T> mapNumber,
      Function<Boolean, ? extends T> mapBoolean) {}
}
