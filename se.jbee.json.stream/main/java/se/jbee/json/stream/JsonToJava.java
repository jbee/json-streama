package se.jbee.json.stream;

import static java.lang.String.format;
import static java.util.function.Function.identity;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Type conversion or mapping is done in two steps:
 *
 * <ol>
 *   <li>a JSON value is parsed into its Java equivalent ({@link String}, {@link Number}, {@link
 *       Boolean}, {@link List}, {@link Map}). Arrays and objects are only mapped when not stream
 *       processed.
 *   <li>the Java equivalent type is mapped to the target type as modelled by the user in the proxy
 *       interface
 * </ol>
 *
 * The {@link JsonToJava} is the mapping configuration used to perform the 2nd step.
 *
 * <p>A {@link Factory} is used to automatically fill in mappings that have not been made explicitly
 * ahead of time. For example, to be able to map to any {@link Enum} value a {@link Factory} creates
 * the mapping {@link Function}s on demand.
 */
@FunctionalInterface
public interface JsonToJava {

  /**
   * Conversion of any simple JSON value to a specific Java type.
   *
   * <p>Usually a target Java type is a simple value type but this is also used for collections and
   * streaming types, mostly to get the adequate {@code null} value.
   *
   * @param to target Java type
   * @param mapNull yields the value or constant to use for JSON null and undefined members
   * @param mapString converts a JSON string to the target Java type
   * @param mapNumber converts a JSON number to the target Java type
   * @param mapBoolean converts a JSON boolean to the target Java type
   * @param <T> the target Java type
   */
  record JsonTo<T>(
      Class<T> to,
      Supplier<T> mapNull,
      Function<String, ? extends T> mapString,
      Function<Number, ? extends T> mapNumber,
      Function<Boolean, ? extends T> mapBoolean) {

    public JsonTo<T> mapNull(T to) {
      return mapNull(new ConstantNull<>(to));
    }

    public JsonTo<T> mapNull(Supplier<T> to) {
      return new JsonTo<>(this.to, to, mapString, mapNumber, mapBoolean);
    }

    public JsonTo<T> mapString(Function<String, ? extends T> mapString) {
      return new JsonTo<>(to, mapNull, mapString, mapNumber, mapBoolean);
    }

    public JsonTo<T> mapNumber(Function<Number, ? extends T> mapNumber) {
      return new JsonTo<>(to, mapNull, mapString, mapNumber, mapBoolean);
    }

    public JsonTo<T> mapBoolean(Function<Boolean, ? extends T> mapBoolean) {
      return new JsonTo<>(to, mapNull, mapString, mapNumber, mapBoolean);
    }
  }

  record ConstantNull<T>(T get) implements Supplier<T> {}

  /**
   * @param to the Java target type for all 3 conversions and the null value
   * @return a bundle of converter function all targeting the provided target Java type but with
   *     different JSON input values (or more precise their Java equivalent)
   * @param <T> the Java target type
   */
  <T> JsonTo<T> mapTo(Class<T> to);

  /*
  Utility and setup of the default conversions
   */

  JsonToJava ROOT = JsonToJava::returnsNull;
  JsonToJava DEFAULT =
      ROOT.with(Serializable.class, null, identity())
          .with(String.class, null, identity(), Objects::toString, Objects::toString)
          .with(
              Character.class, null, s -> s.charAt(0), n -> (char) n.intValue(), b -> b ? 't' : 'f')
          .with(
              char.class, (char) 0, s -> s.charAt(0), n -> (char) n.intValue(), b -> b ? 't' : 'f')
          .with(Number.class, null, JsonParser::parseNumber, identity(), b -> b ? 1 : 0)
          .with(Integer.class, null, Integer::valueOf, Number::intValue, b -> b ? 1 : 0)
          .with(int.class, 0, Integer::valueOf, Number::intValue, b -> b ? 1 : 0)
          .with(Long.class, null, Long::valueOf, Number::longValue, b -> b ? 1L : 0L)
          .with(long.class, 0L, Long::valueOf, Number::longValue, b -> b ? 1L : 0L)
          .with(Float.class, null, Float::valueOf, Number::floatValue, b -> b ? 1f : 0f)
          .with(float.class, 0f, Float::valueOf, Number::floatValue, b -> b ? 1f : 0f)
          .with(Double.class, null, Double::valueOf, Number::doubleValue, b -> b ? 1d : 0d)
          .with(double.class, 0d, Double::valueOf, Number::doubleValue, b -> b ? 1d : 0d)
          .with(Boolean.class, null, Boolean::valueOf, n -> n.doubleValue() == 0d, identity())
          .with(boolean.class, false, Boolean::parseBoolean, n -> n.doubleValue() == 0d, identity())
          .with(List.class, List::of, List::of)
          .with(Collection.class, List::of, List::of)
          .with(Set.class, Set::of, Set::of)
          .with(Stream.class, Stream::empty, Stream::of)
          .with(Map.class, Map::of, JsonToJava::mapValueOf)
          .with(Iterator.class, Collections::emptyIterator, JsonToJava::iteratorValueOf)
          .with(null, Factory.NEW_INSTANCE)
          .immutable();

  /**
   * @return This mapping but any subsequent call to any {@link #with(JsonTo)} method variant will
   *     start with a mutable copy of this mapping. This mapping will not be affected by any further
   *     calls.
   */
  default JsonToJava immutable() {
    return this;
  }

  default <T> JsonToJava with(Class<T> to, UnaryOperator<JsonTo<T>> change) {
    return with(change.apply(mapTo(to)));
  }

  /**
   * Adds or replaces a mapping to the provided target type using the provided {@link Factory} to
   * create the mapping {@link Function}s.
   *
   * @param to any Java target type
   * @param factory used to create the 3 mapping {@link Function}s for {@link String}, {@link
   *     Number} and {@link Boolean}
   * @return this mapping with the added or replaced mapping. Weather this mapping is affected or a
   *     modified copy is returned depends on its {@link #immutable()} state
   * @param <T> any Java target type as used in user proxy interface method return types
   */
  default <T> JsonToJava with(Class<T> to, Factory factory) {
    return with(
        to,
        null,
        factory.create(String.class, to),
        factory.create(Number.class, to),
        factory.create(Boolean.class, to));
  }

  @SuppressWarnings("unchecked")
  default <T> JsonToJava with(Class<T> to, Supplier<T> mapNull, Function<?, ? extends T> mapAny) {
    return with(
        new JsonTo<>(
            to,
            mapNull == null ? new ConstantNull<>(null) : mapNull,
            (Function<String, T>) mapAny,
            (Function<Number, T>) mapAny,
            (Function<Boolean, T>) mapAny));
  }

  default <T> JsonToJava with(
      Class<T> to,
      T mapNull,
      Function<String, ? extends T> mapString,
      Function<Number, ? extends T> mapNumber,
      Function<Boolean, ? extends T> mapBoolean) {
    return with(new JsonTo<>(to, new ConstantNull<>(mapNull), mapString, mapNumber, mapBoolean));
  }

  default <T> JsonToJava with(JsonTo<T> to) {
    record Registry(boolean isImmutable, Factory factory, Map<Class<?>, JsonTo<?>> converters)
        implements JsonToJava {

      @SuppressWarnings("unchecked")
      @Override
      public <X> JsonTo<X> mapTo(Class<X> to) {
        return (JsonTo<X>) converters.computeIfAbsent(to, this::create);
      }

      private <X> JsonTo<X> create(Class<X> to) {
        return new JsonTo<>(
            to,
            new ConstantNull<>(null),
            factory.create(String.class, to),
            factory.create(Number.class, to),
            factory.create(Boolean.class, to));
      }

      @Override
      public <X> JsonToJava with(JsonTo<X> to) {
        if (isImmutable)
          return new Registry(false, factory, new ConcurrentHashMap<>(converters)).with(to);
        converters.put(to.to(), to);
        return this;
      }

      @Override
      public <T> JsonToJava with(Class<T> to, Factory factory) {
        if (to == null || to == Object.class) return new Registry(isImmutable, factory, converters);
        return JsonToJava.super.with(to, factory);
      }

      @Override
      public JsonToJava immutable() {
        return isImmutable ? this : new Registry(true, factory, converters);
      }
    }
    return new Registry(false, Factory.NULL, new ConcurrentHashMap<>()).with(to);
  }

  interface Factory {

    /** Any value maps to {@code null} */
    Factory NULL = Factory::mapToNull;

    /** Any call to a mapping {@link Function} throws an {@link UnsupportedOperationException} */
    Factory UNSUPPORTED = Factory::mapAndThrow;

    /**
     * A constructor with suiting parameter type used, or a call throws a {@link
     * UnsupportedOperationException}.
     */
    Factory NEW_INSTANCE = Factory::mapToNewInstance;

    /**
     * Create a mapping function (converter) to convert from raw JSON value equivalent to target
     * Java value.
     *
     * @param from {@link String}, {@link Number} or {@link Boolean}
     * @param to any java target type as used in proxy interfaces
     * @return the converter to use, never {@code null} but the converter might always return {@code
     *     null}
     * @param <A> JSON value equivalent Java type, one of {@link String}, {@link Number} or {@link
     *     Boolean}
     * @param <B> any target Java type
     */
    <A, B> Function<A, B> create(Class<A> from, Class<B> to);

    private static <A, B> Function<A, B> mapToNull(Class<A> from, Class<B> to) {
      return json -> null;
    }

    private static <A, B> Function<A, B> mapAndThrow(Class<A> from, Class<B> to) {
      return json -> {
        throw new UnsupportedOperationException(
            format(
                "Unknown conversion from value `%s` (%s) to %s",
                json, from.getSimpleName(), to.getSimpleName()));
      };
    }

    private static <A, B> Function<A, B> mapToNewInstance(Class<A> from, Class<B> to) {
      if (to.isInterface()) return UNSUPPORTED.create(from, to);
      if (to.isEnum()) return mapToEnum(from, to);
      try {
        Constructor<B> c = to.getConstructor(from);
        return value -> {
          try {
            return c.newInstance(value);
          } catch (Exception e) {
            throw new JsonMappingException(
                format(
                    "Failed to create instance value of type %s from input: %s",
                    to.getName(), value),
                e);
          }
        };
      } catch (NoSuchMethodException e) {
        // TODO factory method?
        // TODO make thos composeable with a orElse() ? so one can chain:
        // mapToNewInstance.orElse(mapToFactoryMethod).orElse(mapAndThrow)
        return UNSUPPORTED.create(from, to);
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

  /*
  Helper functions
   */

  private static <T> JsonTo<T> returnsNull(Class<T> to) {
    return new JsonTo<>(to, new ConstantNull<>(null), s -> null, n -> null, b -> null);
  }

  private static <E> Iterator<E> iteratorValueOf(E e) {
    return List.of(e).iterator();
  }

  private static <E> Map<String, E> mapValueOf(E e) {
    return Map.of("value", e);
  }
}
