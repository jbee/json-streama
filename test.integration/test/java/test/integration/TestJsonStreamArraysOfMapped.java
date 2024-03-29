package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonStream;

/**
 * Tests scenarios where the streaming is done from an array in the input processed as some
 * streaming java type of directly mapped simple values.
 */
class TestJsonStreamArraysOfMapped {

  @Test
  void arrayStreamOfDirectlyMappedValues() {
    String json = // language=JSON
        """
        {
        "values": [1,2,3]
        }
        """;
    interface Root {
      Stream<Integer> values();
    }

    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    assertEquals(List.of(1, 2, 3), root.values().toList());
  }

  @Test
  void arrayIteratorOfDirectlyMappedValues() {
    String json = // language=JSON
        """
        {
        "values": ["a", "b", "c"]
        }
        """;
    interface Root {
      Iterator<String> values();
    }

    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    List<String> actual = new ArrayList<>();
    root.values().forEachRemaining(actual::add);
    assertEquals(List.of("a", "b", "c"), actual);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {
      "a": ["1", "2"],
      "b": ["3", "4"]
      }
      """,
        // language=JSON
        """
      {
      "b": ["3", "4"],
      "a": ["1", "2"]
      }
      """
      })
  void arrayConsumerOfDirectlyMappedValues(String json) {
    interface Root {
      void a(Consumer<String> forEach);

      void b(Consumer<String> forEach);
    }
    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    List<String> as = new ArrayList<>();
    List<String> bs = new ArrayList<>();
    root.a(as::add);
    root.b(bs::add);
    assertEquals(List.of("1", "2"), as);
    assertEquals(List.of("3", "4"), bs);
  }

  @Test
  void arrayConsumerOfDirectlyMappedValues() {
    String json = // language=JSON
        """
        {
        "values": [true, false, true]
        }
        """;
    interface Root {
      void values(Consumer<Boolean> forEach);
    }

    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    List<Boolean> actual = new ArrayList<>();
    root.values(actual::add);
    assertEquals(List.of(true, false, true), actual);
  }

  @Test
  void arrayStreamOfDirectlyMappedEntryValues() {
    String json = // language=JSON
        """
        {
        "values": [1.3,2.4,3.5]
        }
        """;
    interface Root {
      Stream<Map.Entry<Integer, Double>> values();
    }

    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    assertEquals(
        List.of(new SimpleEntry<>(0, 1.3d), new SimpleEntry<>(1, 2.4d), new SimpleEntry<>(2, 3.5)),
        root.values().toList());
  }

  @Test
  void arrayStreamOfDirectlyMappedListValues() {
    String json = // language=JSON
        """
        {
        "values": [[1,2],[3,4],[5,6]]
        }
        """;
    interface Root {
      Stream<List<Integer>> values();
    }

    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    assertEquals(List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6)), root.values().toList());
  }

  @Test
  void nullArrayStreamOfDirectlyMappedValues() {
    String json = // language=JSON
        """
        {
        "values": null,
        "afterNull": [1]
        }
        """;
    interface Root {
      Stream<Integer> values();

      Stream<Integer> afterNull();
    }

    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    assertEquals(
        Stream.empty().toList(), root.values().toList(), "null is not resulting in empty stream");
    assertEquals(
        List.of(1), root.afterNull().toList(), "next streaming member not continued correctly");
  }

  // TODO tests with multiple streaming members, also to see if the iterator completes the parsing
  // at end of array/object cleanly
}
