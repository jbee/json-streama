package se.jbee.json.stream;

public interface JsonMapping {

	static JsonMapping auto() {
		return AutoJsonMapper.SHARED;
	}

	<T> JsonMapper<T> mapTo(Class<T> to);
}
