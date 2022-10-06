package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.integration.Utils.asJsonInput;

import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonStream;
import test.integration.Model.Album;

/** See if the {@link JsonStream} can handle skipping unmapped members. */
class TestJsonStreamFeatureIgnoreMembers {

  @Test
  void skipNumberMember() {
    String json = // language=JSON
        """
    {
      "name": "Bone Machine",
      "skipped1": 42,
      "skipped2": 42.0,
      "skipped3": -43,
      "skipped4": 12.34e+34,
      "skipped5": 12.34e-34,
      "artist": "Tom Waits",
      "genre": "Jazz"
    }
    """;
    assertSkippingToArtist(json);
  }

  @Test
  void skipBooleanMember() {
    String json = // language=JSON
        """
    {
      "name": "Bone Machine",
      "skipped1": true,
      "skipped2": false,
      "artist": "Tom Waits",
      "genre": "Jazz"
    }
    """;
    assertSkippingToArtist(json);
  }

  @Test
  void skipNullMember() {
    String json = // language=JSON
        """
    {
      "name": "Bone Machine",
      "skipped1": null,
      "artist": "Tom Waits",
      "genre": "Jazz"
    }
    """;
    assertSkippingToArtist(json);
  }

  @Test
  void skipStringMember() {
    String json = // language=JSON
        """
    {
      "name": "Bone Machine",
      "skipped1": "",
      "skipped2": "a",
      "skipped3": "hello",
      "skipped4": "hello\\"",
      "skipped5": "hello\\u6666",
      "artist": "Tom Waits",
      "genre": "Jazz"
    }
    """;
    assertSkippingToArtist(json);
  }

  @Test
  void skipArrayMember() {
    String json = // language=JSON
        """
    {
      "name": "Bone Machine",
      "skipped1": [],
      "skipped2": [1],
      "skipped3":  ["hello" , 42],
      "skipped4": [[]],
      "skipped5": [[1,4],[true, false]],
      "artist": "Tom Waits",
      "genre": "Jazz"
    }
    """;
    assertSkippingToArtist(json);
  }

  @Test
  void skipObjectMember() {
    String json = // language=JSON
        """
    {
      "name": "Bone Machine",
      "skipped1": {},
      "skipped2": {"a": null},
      "skipped3": {"a": true, "b": false},
      "skipped4": {"ab": {"a": 1, "b": 2}},
      "artist": "Tom Waits",
      "genre": "Jazz"
    }
    """;
    assertSkippingToArtist(json);
  }

  private static void assertSkippingToArtist(String json) {
    Album album = JsonStream.ofRoot(Album.class, asJsonInput(json));
    assertEquals("Tom Waits", album.artist());
    assertEquals("Bone Machine", album.name());
  }
}
