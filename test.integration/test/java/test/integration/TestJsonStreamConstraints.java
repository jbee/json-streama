package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonConstraintException;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonProperty;
import se.jbee.json.stream.JsonStream;

/**
 * Tests the validation aspects of {@link se.jbee.json.stream.JsonStream}s.
 *
 * <p>These are the {@link JsonProperty#minOccur()}, {@link JsonProperty#maxOccur()} and {@link
 * JsonProperty#required()} restrictions.
 */
class TestJsonStreamConstraints {

  interface ProxyStreamRoot {
    @JsonProperty(maxOccur = 3)
    Stream<Element> max3();

    @JsonProperty(minOccur = 2)
    Stream<Element> min2();
  }

  interface Element {
    String name();
  }

  @Test
  void maxOccur_ProxyStream_UpToLimitCanOccur() {
    String json = // language=JSON
        """
        {
          "max3": [{"name": "A"}, {"name": "B"}, {"name": "C"}]
        }""";

    ProxyStreamRoot root = JsonStream.ofRoot(ProxyStreamRoot.class, JsonInputStream.of(json));
    assertEquals(List.of("A", "B", "C"), root.max3().map(Element::name).toList());
  }

  @Test
  void maxOccur_ProxyStream_TooManyItemsThrowsException() {
    String json = // language=JSON
        """
        {
          "max3": [{"name": "A"}, {"name": "B"}, {"name": "C"}, {"name": "D"}]
        }""";

    ProxyStreamRoot root = JsonStream.ofRoot(ProxyStreamRoot.class, JsonInputStream.of(json));
    Stream<Element> max3 = root.max3();
    JsonConstraintException ex =
        assertThrows(JsonConstraintException.class, () -> max3.forEach(Element::name));
    assertEquals("Maximum expected number of Element items is 3.", ex.getMessage());
  }

  @Test
  void minOccur_ProxyStream_LimitCanOccur() {
    String json = // language=JSON
        """
        {
          "min2": [{"name": "A"}, {"name": "B"}]
        }""";

    ProxyStreamRoot root = JsonStream.ofRoot(ProxyStreamRoot.class, JsonInputStream.of(json));
    assertEquals(List.of("A", "B"), root.min2().map(Element::name).toList());
  }

  @Test
  void minOccur_ProxyStream_TooLittleItemsThrowsException() {
    String json = // language=JSON
        """
        {
          "min2": [{"name": "A"}]
        }""";

    ProxyStreamRoot root = JsonStream.ofRoot(ProxyStreamRoot.class, JsonInputStream.of(json));
    Stream<Element> min2 = root.min2();
    JsonConstraintException ex =
        assertThrows(JsonConstraintException.class, () -> min2.forEach(Element::name));
    assertEquals(
        "Minimum expected number of Element items is 2 but only found 1.", ex.getMessage());
  }

  @Test
  void minOccur_ProxyStream_NullThrowsException() {
    String json = // language=JSON
        """
        {
          "min2": null
        }""";

    ProxyStreamRoot root = JsonStream.ofRoot(ProxyStreamRoot.class, JsonInputStream.of(json));
    // note that for null the exception occurs when accessing the member
    // whereas too little elements are found once the stream is processed
    JsonConstraintException ex = assertThrows(JsonConstraintException.class, root::min2);
    assertEquals(
        "Minimum expected number of Element items is 2 but only found 0.", ex.getMessage());
  }

  @Test
  void minOccur_ProxyStream_UndefinedThrowsException() {
    String json = // language=JSON
        """
        {}""";

    ProxyStreamRoot root = JsonStream.ofRoot(ProxyStreamRoot.class, JsonInputStream.of(json));
    // note that for null the exception occurs when accessing the member
    // whereas too little elements are found once the stream is processed
    JsonConstraintException ex = assertThrows(JsonConstraintException.class, root::min2);
    assertEquals(
        "Minimum expected number of Element items is 2 but only found 0.", ex.getMessage());
  }

  // TODO
  // - stream null => min occur
  // min/max occur collections
}
