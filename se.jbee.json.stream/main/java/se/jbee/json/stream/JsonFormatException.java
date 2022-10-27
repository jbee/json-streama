package se.jbee.json.stream;

import static java.util.stream.Collectors.joining;

/**
 * Indicates that the input wasn't valid JSON.
 *
 * <p>In other words that input is malformed JSON.
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
      int found, String inputPosition, char[] expected) {
    return unexpectedInputCharacter(found, inputPosition, toExpectedList(expected));
  }

  public static JsonFormatException unexpectedInputCharacter(
      int found, String inputPosition, String expected) {
    String foundText = found == -1 ? "end of input" : "`" + Character.toString(found) + "`";
    return new JsonFormatException(
        "Expected " + expected + " but found: " + foundText + "\nat: " + inputPosition);
  }

  private static String toExpectedList(char[] expected) {
    return expected.length == 0
        ? "more input"
        : "one of "
            + new String(expected)
                .chars()
                .mapToObj(c -> "`" + (char) c + "`")
                .collect(joining(","));
  }
}
