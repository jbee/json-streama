package se.jbee.json.stream;

import java.util.function.Function;

public interface JsonMapping {

	static JsonMapping auto() {
		return AutoJsonMapper.SHARED;
	}

	<T> JsonTo<T> mapTo(Class<T> to);

	record JsonTo<T>(
			Class<T> to,
			Function<String, T> mapString,
			Function<Number, T> mapNumber,
			Function<Boolean, T> mapBoolean
	) {}
}
