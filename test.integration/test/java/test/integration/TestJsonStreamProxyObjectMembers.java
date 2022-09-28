package test.integration;

import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.integration.Utils.asJsonInput;

class TestJsonStreamProxyObjectMembers {

  interface Entry {
    Entry down();
    String content();
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
              }
            }
          }
            """;
    Entry root = JsonStream.ofRoot(Entry.class, asJsonInput(json));
    assertEquals("hello", root.down().down().content());
    assertEquals("hallo", root.down().content());
  }
}
