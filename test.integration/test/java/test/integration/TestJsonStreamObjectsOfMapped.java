package test.integration;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.integration.Utils.asJsonInput;

import java.lang.annotation.RetentionPolicy;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonStream;

/**
 * Test scenarios where the streaming is done from an object "map" in the input processed as some
 * streaming java type of directly mapped simple value entries.
 */
class TestJsonStreamObjectsOfMapped {

  @Test
  void objectStreamOfDirectlyMappedValues() {
    String json = // language=JSON
        """
        {
        "values": { "a":1,"b":2,"c":3}
        }
        """;
    interface Root {
      Stream<Entry<String, Integer>> values();
    }

    Root root = JsonStream.ofRoot(Root.class, asJsonInput(json));
    assertEquals(
        List.of(entry("a", 1), entry("b", 2), entry("c", 3)), root.values().collect(toList()));
  }

  @Test
  void objectIteratorOfDirectlyMappedValues() {
    String json = // language=JSON
        """
        {
        "values": {"1":"a", "2": "b","3": "c" }
        }
        """;
    interface Root {
      Iterator<Entry<Integer, String>> values();
    }

    Root root = JsonStream.ofRoot(Root.class, asJsonInput(json));
    var actual = new ArrayList<>();
    root.values().forEachRemaining(actual::add);
    assertEquals(List.of(entry(1, "a"), entry(2, "b"), entry(3, "c")), actual);
  }

  @Test
  void objectConsumerOfDirectlyMappedValues() {
    String json = // language=JSON
        """
        {
        "values": {"SOURCE":true, "RUNTIME":false, "CLASS":true}
        }
        """;
    interface Root {
      void values(Consumer<Entry<RetentionPolicy, Boolean>> forEach);
    }

    Root root = JsonStream.ofRoot(Root.class, asJsonInput(json));
    var actual = new ArrayList<>();
    root.values(actual::add);
    assertEquals(
        List.of(
            entry(RetentionPolicy.SOURCE, true),
            entry(RetentionPolicy.RUNTIME, false),
            entry(RetentionPolicy.CLASS, true)),
        actual);
  }

  private static <K, V> SimpleEntry<K, V> entry(K key, V value) {
    return new SimpleEntry<>(key, value);
  }
}
