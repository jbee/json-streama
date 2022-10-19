package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.integration.Utils.asJsonInput;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonStream;

class TestJsonStreamAnyOtherValues {

  interface AnyValuesRoot {

    String something();

    Map<String, ?> anyOtherValues();
  }

  @Test
  void anyOtherValues_None() {
    String json = // language=JSON
        """
            {}
            """;
    AnyValuesRoot root = JsonStream.ofRoot(AnyValuesRoot.class, asJsonInput(json));
    assertEquals(0, root.anyOtherValues().size());
    assertNull(root.something());
  }

  @Test
  void anyOtherValues_Object() {
    String json = // language=JSON
        """
        {
        "hello": "world",
        "answer": 42,
        "truth": true,
        "list":[1,2,3],
        "map": {"a": 5},
        "null": null,
        "something": "deep blue"
        }
        """;
    AnyValuesRoot root = JsonStream.ofRoot(AnyValuesRoot.class, asJsonInput(json));
    Map<String, ?> anyValues = root.anyOtherValues();
    assertEquals(6, anyValues.size());
    assertEquals("world", anyValues.get("hello"));
    assertEquals(42, anyValues.get("answer"));
    assertEquals(true, anyValues.get("truth"));
    assertEquals(List.of(1, 2, 3), anyValues.get("list"));
    assertEquals(Map.of("a", 5), anyValues.get("map"));
    assertNull(anyValues.get("null"));
    assertTrue(anyValues.containsKey("null"));
    assertNull(anyValues.get("other"));
    assertEquals("deep blue", root.something());
  }

  @Test
  void anyOtherValues_ArrayStream() {
    String json = // language=JSON
        """
        [{ "a": "b"}, {"c":"d"}, {"e":  "f"}]
        """;
    Iterator<AnyValuesRoot> iter = JsonStream.of(AnyValuesRoot.class, asJsonInput(json)).iterator();
    assertEquals(Map.of("a", "b"), iter.next().anyOtherValues());
    assertEquals(Map.of("c", "d"), iter.next().anyOtherValues());
    assertEquals(Map.of("e", "f"), iter.next().anyOtherValues());
    assertFalse(iter.hasNext());
  }
}
