package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonStream;

/**
 * Tests that focus on scenarios where single {@link java.lang.reflect.Proxy} backed user types
 * occur in the structure.
 */
class TestJsonStreamSingleProxies {

  interface Entry {
    Entry down();

    String content();

    Entry another();
  }

  @Test
  void test() {
    String json = // language=JSON
        """
          {
            "down": {
              "content": "hallo",
              "down": {
                "content": "hello"
              },
              "another": { "content": "here" }
            }
          }
            """;
    Entry root = JsonStream.ofRoot(Entry.class, JsonInputStream.of(json));
    Entry down1 = root.down();
    assertEquals("hallo", down1.content());
    assertEquals("hello", down1.down().content());
    assertEquals("here", down1.another().content());
  }
}
