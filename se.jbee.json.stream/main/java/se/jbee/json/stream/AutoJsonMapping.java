package se.jbee.json.stream;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class AutoJsonMapping implements JsonMapping {

	private final Map<Class<?>, Function<String, ?>> fromString = new HashMap<>();
	private final Map<Class<?>, Function<Number, ?>> fromNumber = new HashMap<>();
	private final Map<Class<?>, Function<Boolean, ?>> fromBoolean = new HashMap<>();

	@Override
	@SuppressWarnings("unchecked")
	public <T> T mapString(String from, Class<T> to) {
		return (T) fromString.computeIfAbsent(to, key -> autodetect(String.class, key)).apply(from);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T mapNumber(Number from, Class<T> to) {
		return (T) fromNumber.computeIfAbsent(to, key -> autodetect(Number.class, key)).apply(from);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T mapBoolean(Boolean from, Class<T> to) {
		return (T) fromBoolean.computeIfAbsent(to, key -> autodetect(Boolean.class, key)).apply(from);
	}

	private <A, B> Function<A, B> autodetect(Class<A> from, Class<B> to) {
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
}
