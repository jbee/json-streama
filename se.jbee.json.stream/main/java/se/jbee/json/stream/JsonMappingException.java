package se.jbee.json.stream;

/**
 * Thrown when a Java equivalent value of the JSON input could not be converted to the Java target
 * type as present in the method return type.
 */
public final class JsonMappingException extends JsonProcessingException {
  public JsonMappingException(String message, Exception cause) {
    super(message, cause);
  }
}
