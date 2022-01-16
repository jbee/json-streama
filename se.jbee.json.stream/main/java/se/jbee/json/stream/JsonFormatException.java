package se.jbee.json.stream;

import static java.util.stream.Collectors.joining;

import java.util.function.Supplier;

/**
 * Indicates that the input wasn't valid JSON.
 *
 * @author Jan Bernitt
 * @since 1.0
 * @see JsonSchemaException
 */
public class JsonFormatException extends JsonProcessingException {

  public JsonFormatException(String s) {
    super(s);
  }

  public static JsonFormatException unexpectedInputCharacter(
      int found, Supplier<String> inputPosition, char[] expected) {
    String foundText = found == -1 ? "end of input" : "`" + Character.toString(found) + "`";
    String expectedText =
        expected.length == 0 ? "more input" : "one of " + toExpectedList(expected);
    return new JsonFormatException(
        "Expected " + expectedText + " but found: " + foundText + "\nat: " + inputPosition.get());
  }

  private static String toExpectedList(char[] expected) {
    return new String(expected).chars().mapToObj(c -> "`" + (char) c + "`").collect(joining(","));
  }
}
