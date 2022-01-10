package se.jbee.json.stream;

import se.jbee.json.stream.JsonMapping.JsonTo;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class AutoJsonMapper {

	private AutoJsonMapper() {
		throw new UnsupportedOperationException("util");
	}

	private static final Map<Class<?>, JsonTo<?>> MAPPER_BY_TO_TYPE = new ConcurrentHashMap<>();

	static final JsonMapping SHARED = AutoJsonMapper::createMapperCached;

	//TODO move cache to interface

	@SuppressWarnings("unchecked")
	private static <T> JsonTo<T> createMapperCached(Class<T> to) {
		return (JsonTo<T>) MAPPER_BY_TO_TYPE.computeIfAbsent(to, AutoJsonMapper::createMapper);
	}

	private static <T> JsonTo<T> createMapper(Class<T> to) {
		return new JsonTo<>(to, detect(String.class, to), detect(Number.class, to), detect(Boolean.class, to));
	}

	private static <A, B> Function<A, B> detect(Class<A> from, Class<B> to) {
		if (to.isEnum())
			return mapToEnum(from, to);
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
			return JsonMapping.unsupported(from, to);
		}
	}

	@SuppressWarnings("unchecked")
	private static <A, B> Function<A, B> mapToEnum(Class<A> from, Class<B> to) {
		B[] constants = to.getEnumConstants();
		if (from == String.class) return name -> (B) wrap(name, to);
		if (from == Number.class) return ordinal -> constants[((Number)ordinal).intValue()];
		return flag -> constants[flag == Boolean.FALSE ? 0 : 1];
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static <E extends Enum<E>> Enum<?> wrap(Object from, Class to) {
		return Enum.valueOf(to, from.toString());
	}

}
