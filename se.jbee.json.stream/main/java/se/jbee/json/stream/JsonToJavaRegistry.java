package se.jbee.json.stream;

import static java.util.Collections.emptyIterator;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import se.jbee.json.stream.JsonToJava.JsonTo;

final class JsonToJavaRegistry {

  private JsonToJavaRegistry() {
    throw new UnsupportedOperationException("util");
  }

  private static final Map<Class<?>, JsonTo<?>> TO_JAVA_BY_TARGET_TYPE = new ConcurrentHashMap<>();

  public static <T> void register(JsonTo<T> by) {
    TO_JAVA_BY_TARGET_TYPE.put(by.to(), by);
  }

  public static void register(Class<?> to) {
    register(getSimpleValueMapping(to));
  }

  public static <T> void register(
      Class<T> to,
      T mapNull,
      Function<String, ? extends T> mapString,
      Function<Number, ? extends T> mapNumber,
      Function<Boolean, ? extends T> mapBoolean) {
    register(new JsonTo<>(to, mapNull, mapString, mapNumber, mapBoolean));
  }

  public static <T> void registerNull(Class<T> to, T mapNull) {
    register(new JsonTo<>(to, mapNull, null, null, null));
  }

  static {
    // TODO make this a default method "with" which normally wraps this on but on  a map it just
    // adds
    register(
        Serializable.class, null, Function.identity(), Function.identity(), Function.identity());
    register(String.class, null, Function.identity(), Objects::toString, Objects::toString);
    register(Number.class, null, JsonReader::parseNumber, Function.identity(), b -> b ? 1 : 0);
    register(Integer.class, null, Integer::valueOf, Number::intValue, b -> b ? 1 : 0);
    register(int.class, 0, Integer::valueOf, Number::intValue, b -> b ? 1 : 0);
    register(Long.class, null, Long::valueOf, Number::longValue, b -> b ? 1L : 0L);
    register(long.class, 0L, Long::valueOf, Number::longValue, b -> b ? 1L : 0L);
    register(Float.class, null, Float::valueOf, Number::floatValue, b -> b ? 1f : 0f);
    register(float.class, 0f, Float::valueOf, Number::floatValue, b -> b ? 1f : 0f);
    register(Double.class, null, Double::valueOf, Number::doubleValue, b -> b ? 1d : 0d);
    register(double.class, 0d, Double::valueOf, Number::doubleValue, b -> b ? 1d : 0d);
    register(
        Boolean.class,
        null,
        Boolean::valueOf,
        JsonToJavaRegistry::mapNumberToBoolean,
        Function.identity());
    register(
        boolean.class,
        false,
        Boolean::parseBoolean,
        JsonToJavaRegistry::mapNumberToBoolean,
        Function.identity());

    // complex types for null
    register(List.class, List.of(), List::of, List::of, List::of);
    register(Collection.class, List.of(), List::of, List::of, List::of);
    register(Set.class, Set.of(), Set::of, Set::of, Set::of);
    register(
        Iterator.class,
        emptyIterator(),
        e -> List.of(e).iterator(),
        e -> List.of(e).iterator(),
        e -> List.of(e).iterator());
    register(
        Map.class,
        Map.of(),
        e -> Map.of("value", e),
        e -> Map.of("value", e),
        e -> Map.of("value", e));
    Function<String, Stream<String>> ofString = Stream::of;
    Function<Number, Stream<Number>> ofNumber = Stream::of;
    Function<Boolean, Stream<Boolean>> ofBoolean = Stream::of;
    register(Stream.class, Stream.empty(), ofString, ofNumber, ofBoolean);
  }

  private static boolean mapNumberToBoolean(Number n) {
    return n.doubleValue() == 0d;
  }

  static final JsonToJava JVM_SHARED = JsonToJavaRegistry::getSimpleValueMapping;

  // TODO move cache to interface => class that has a map where one can add - otherwise this is
  // wrapped and chained

  @SuppressWarnings("unchecked")
  private static <T> JsonTo<T> getSimpleValueMapping(Class<T> to) {
    return (JsonTo<T>)
        TO_JAVA_BY_TARGET_TYPE.computeIfAbsent(to, JsonToJavaRegistry::createSimpleValueMapping);
  }

  private static <T> JsonTo<T> createSimpleValueMapping(Class<T> to) {
    return new JsonTo<>(
        to, null, detect(String.class, to), detect(Number.class, to), detect(Boolean.class, to));
  }

  private static <A, B> Function<A, B> detect(Class<A> from, Class<B> to) {
    if (to.isEnum()) return mapToEnum(from, to);
    try {
      Constructor<B> c = to.getConstructor(from);
      return value -> {
        try {
          return c.newInstance(value);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      };
    } catch (NoSuchMethodException e) {
      return JsonToJava.unsupported(from, to);
    }
  }

  @SuppressWarnings("unchecked")
  private static <A, B> Function<A, B> mapToEnum(Class<A> from, Class<B> to) {
    B[] constants = to.getEnumConstants();
    if (from == String.class) return name -> (B) wrap(name, to);
    if (from == Number.class) return ordinal -> constants[((Number) ordinal).intValue()];
    return flag -> constants[flag == Boolean.FALSE ? 0 : 1];
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <E extends Enum<E>> Enum<?> wrap(Object from, Class to) {
    return Enum.valueOf(to, from.toString());
  }
}
