package se.jbee.json.stream;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

record AutoJsonMapper<T>(Class<T> to, Function<String, T> mapString, Function<Number, T> mapNumber, Function<Boolean, T> mapBoolean) implements JsonMapper<T> {

	private static final Map<Class<?>, JsonMapper<?>> MAPPER_BY_TO_TYPE = new ConcurrentHashMap<>();

	static final JsonMapping SHARED = AutoJsonMapper::createMapperCached;

	@SuppressWarnings("unchecked")
	private static <T> JsonMapper<T> createMapperCached(Class<T> to) {
		return (JsonMapper<T>) MAPPER_BY_TO_TYPE.computeIfAbsent(to, AutoJsonMapper::createMapper);
	}

	private static <T> JsonMapper<T> createMapper(Class<T> to) {
		return new AutoJsonMapper<>(to, detect(String.class, to), detect(Number.class, to), detect(Boolean.class, to));
	}

	@Override
	public T mapString(String from) {
		return mapString.apply(from);
	}

	@Override
	public T mapNumber(Number from) {
		return mapNumber.apply(from);
	}

	@Override
	public T mapBoolean(boolean from) {
		return mapBoolean.apply(from);
	}

	@SuppressWarnings("unchecked")
	private static <A, B> Function<A, B> detect(Class<A> from, Class<B> to) {
		if (to.isEnum()) {
			if (from == String.class) return name -> (B) wrap(name, to);
			if (from == Number.class) return ordinal -> to.getEnumConstants()[((Number)ordinal).intValue()];
			if (from == Boolean.class) return flag -> to.getEnumConstants()[flag == Boolean.FALSE ? 0 : 1];
		}
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
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static <E extends Enum<E>> Enum<?> wrap(Object from, Class to) {
		return Enum.valueOf(to, from.toString());
	}

}
