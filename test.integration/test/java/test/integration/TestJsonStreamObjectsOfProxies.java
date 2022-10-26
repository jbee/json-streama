package test.integration;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.integration.Model.PLAYLIST_JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonStream;

/**
 * Tests scenarios where the streaming is done from an object "map" in the input processed as some
 * streaming java type of proxies represented by a user interface.
 */
class TestJsonStreamObjectsOfProxies {

  @Test
  void rootObjectAsProxy() {
    Function<Model.Track, String> describe = track -> //
        String.format(
                "%d. %s %s (%.1f)",
                track.no(), track.name(), "*".repeat(track.stars()), track.averageStars());

    Model.Playlist list =
        JsonStream.ofRoot(Model.Playlist.class, JsonInputStream.of(PLAYLIST_JSON));

    assertEquals("me", list.author());
    assertEquals("Tom Waits Special", list.name());
    assertEquals(
        List.of("1. I Never Talk to Strangers **** (3.9)", "2. Cold cold Ground ***** (4.7)"),
        list.tracks().map(describe).collect(toList()));
  }

  @Test
  void rootObjectAsProxyConsumer() {
    Model.Playlist list =
        JsonStream.ofRoot(Model.Playlist.class, JsonInputStream.of(PLAYLIST_JSON));

    assertEquals("me", list.author());
    assertEquals("Tom Waits Special", list.name());
    List<String> actual = new ArrayList<>();
    list.tracks(
        track -> actual.add(track.no() + ". " + track.name() + " " + ("*".repeat(track.stars()))));
    assertEquals(List.of("1. I Never Talk to Strangers ****", "2. Cold cold Ground *****"), actual);
  }

  @Test
  void objectAsStream_AnnotatedKeyIsAccessibleViaName() {
    String json = // language=JSON
        """
    {
      "name": "Bone Machine",
      "artist": "Tom Waits",
      "genre": "Jazz",
      "tracks": {
        "1": { "name": "Earth Died Screaming"},
        "2": { "name": "Dirt in the Ground"}
      }
    }
    """;
    Model.Album album = JsonStream.ofRoot(Model.Album.class, JsonInputStream.of(json));
    assertEquals(
        List.of("1. Earth Died Screaming", "2. Dirt in the Ground"),
        album.tracks().map(track -> track.no() + ". " + track.name()).collect(toList()));
  }
}
