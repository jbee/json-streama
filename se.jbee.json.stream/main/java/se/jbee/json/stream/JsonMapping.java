package se.jbee.json.stream;

public interface JsonMapping {

	static JsonMapping create() {
		return new AutoJsonMapping();
	}

	<T> T mapString(String from, Class<T> to);

	<T> T mapNumber(Number from, Class<T> to);

	<T> T mapBoolean(Boolean from, Class<T> to);
}
