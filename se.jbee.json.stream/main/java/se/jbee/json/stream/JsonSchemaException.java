package se.jbee.json.stream;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import java.util.stream.Stream;

/**
 * Indicates that the input was valid JSON but that it wasn't valid for the expected shape the input
 * should be mapped to.
 *
 * @author Jan Bernitt
 * @since 1.0
 * @see JsonFormatException
 */
public class JsonSchemaException extends JsonProcessingException {
  public JsonSchemaException(String s) {
    super(s);
  }

  public static JsonSchemaException maxOccurExceeded(Class<?> type, int maxOccur) {
    return new JsonSchemaException(
        format("Maximum expected number of %s items is %d.", type.getSimpleName(), maxOccur));
  }

  public static JsonSchemaException outOfOrder(String name, Stream<String> before) {
    return new JsonSchemaException(
        format(
            "Expected `%s` to occur after %s but encountered it before",
            name, before.map(n -> "`" + n + "`").collect(joining(","))));
  }
}
