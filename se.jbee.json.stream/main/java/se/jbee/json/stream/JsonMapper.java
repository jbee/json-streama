package se.jbee.json.stream;

public interface JsonMapper<T> {

	T mapString(String from);

	T mapNumber(Number from);

	T mapBoolean(boolean from);
}
