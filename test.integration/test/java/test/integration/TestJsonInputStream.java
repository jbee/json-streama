package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonStream;

class TestJsonInputStream {

  interface Root {
    String value();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "hello",
        "@", // 1 byte
        "æ", // 2 bytes
        "€", // 3 bytes
        "\uD83D\uDE00", // 4 bytes
        "hello @€æ \uD83D\uDE00"
      })
  void utf8_byteInputStream(String value) {
    String json = "{\"value\":\"" + value + "\"}";
    Root root =
        JsonStream.ofRoot(Root.class, JsonInputStream.of(json.getBytes(StandardCharsets.UTF_8)));
    assertEquals(value, root.value());
  }
}
