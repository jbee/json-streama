package se.jbee.json.stream;

/**
 * Groups issues while type JSON input.
 *
 * <p>Either the input is malformed causing a {@link JsonFormatException} or the input cannot be
 * mapped to the expected JAVA form causing a {@link JsonSchemaException}.
 *
 * @author Jan Bernitt
 * @since 1.0
 * @see JsonFormatException
 * @see JsonSchemaException
 */
abstract class JsonProcessingException extends IllegalArgumentException {
  protected JsonProcessingException(String s) {
    super(s);
  }

  protected JsonProcessingException(String message, Exception cause) {
    super(message, cause);
  }
}
