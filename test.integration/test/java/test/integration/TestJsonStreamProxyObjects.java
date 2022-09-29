package test.integration;

import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.integration.Utils.asJsonInput;

/**
 * Tests that focus on scenarios where single {@link java.lang.reflect.Proxy} backed user types occur in the structure.
 */
class TestJsonStreamProxyObjects {

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
    Entry root = JsonStream.ofRoot(Entry.class, asJsonInput(json));
    Entry down1 = root.down();
    assertEquals("hallo", down1.content());
    assertEquals("hello", down1.down().content());
    assertEquals("here", down1.another().content());
  }
}
