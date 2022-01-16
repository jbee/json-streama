package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static test.integration.Utils.asJsonInput;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonProperty;
import se.jbee.json.stream.JsonSchemaException;
import se.jbee.json.stream.JsonStream;

/**
 * Tests the validation aspects of {@link se.jbee.json.stream.JsonStream}s.
 *
 * <p>These are the {@link JsonProperty#minOccur()}, {@link JsonProperty#maxOccur()} and {@link
 * JsonProperty#required()} restrictions.
 */
class TestJsonStreamRestrictions {

  interface Root {
    @JsonProperty(maxOccur = 3)
    Stream<Element> max3();
  }

  interface Element {
    String name();
  }

  @Test
  void maxOccur_UpToLimitCanOccur() {
    String json = // language=JSON
        """
        {
          "max3": [{"name": "A"}, {"name": "B"}, {"name": "C"}]
        }""";

    Root root = JsonStream.ofRoot(Root.class, asJsonInput(json));
    assertEquals(List.of("A", "B", "C"), root.max3().map(Element::name).toList());
  }

  @Test
  void maxOccur_TooManyItemsThrowsException() {
    String json = // language=JSON
        """
        {
          "max3": [{"name": "A"}, {"name": "B"}, {"name": "C"}, {"name": "D"}]
        }""";

    Root root = JsonStream.ofRoot(Root.class, asJsonInput(json));
    Stream<Element> max3 = root.max3();
    JsonSchemaException ex =
        assertThrows(JsonSchemaException.class, () -> max3.forEach(Element::name));
    assertEquals("Maximum expected number of Element items is 3.", ex.getMessage());
  }
}
