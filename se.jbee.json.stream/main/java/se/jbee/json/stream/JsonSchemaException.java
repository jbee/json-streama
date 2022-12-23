package se.jbee.json.stream;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import java.util.List;

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

  public static JsonSchemaException outOfOrder(String name, List<String> before) {
    return new JsonSchemaException(
        format(
            "Expected `%s` to occur after %s but encountered it before",
            name, before.stream().map(n -> "`" + n + "`").collect(joining(","))));
  }

  public static JsonSchemaException alreadyProcessed(String name) {
    return new JsonSchemaException(
        format(
            "Member `%s` has already been processed. It appears it is accessed more than once.",
            name));
  }
}
