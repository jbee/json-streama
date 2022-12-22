package test.integration;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonStream;
import test.integration.Model.Track;

class TestJsonStreamArraysOfProxies {

  @Test
  void rootArrayAsProxyStream() {
    String json = // language=JSON
        """
    [
				{"no":1, "name": "Earth Died Screaming"},
				{"no":2, "name": "Dirt in the Ground"}
		]""";
    Stream<Track> items = JsonStream.of(Track.class, JsonInputStream.of(json));

    assertEquals(
        List.of("1. Earth Died Screaming", "2. Dirt in the Ground"),
        items.map(track -> track.no() + ". " + track.name()).collect(toList()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {
      "a": [{"x": 1}, {"x": 2}],
      "b": [{"x": 3}, {"x": 4}]
      }
      """,
        // language=JSON
        """
      {
      "b": [{"x": 3}, {"x": 4}],
      "a": [{"x": 1}, {"x": 2}]
      }
      """
      })
  void arrayConsumerOfProxyValues_Remembered(String json) {
    interface Entry {
      int x();
    }
    interface Root {
      void a(Consumer<Entry> forEach);

      void b(Consumer<Entry> forEach);
    }
    Root root = JsonStream.ofRoot(Root.class, JsonInputStream.of(json));
    List<Integer> as = new ArrayList<>();
    List<Integer> bs = new ArrayList<>();
    root.a(e -> as.add(e.x()));
    root.b(e -> bs.add(e.x()));
    assertEquals(List.of(1, 2), as);
    assertEquals(List.of(3, 4), bs);
  }
}
