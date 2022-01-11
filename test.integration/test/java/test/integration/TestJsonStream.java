package test.integration;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonFormatException;
import se.jbee.json.stream.JsonStream;

class TestJsonStream {

  public enum Genre {
    Jazz,
    Pop
  }

  public interface Playlist {
    String name();

    String author();

    Stream<Track> tracks();

    void tracks(Consumer<Track> consumer);
  }

  public interface Track {

    int no();

    String name();

    String artist();

    long duration();

    int stars();

    float averageStars();
  }

  public interface Album {

    String name();

    String artist();

    Genre genre();

    Iterator<Track> tracks();
  }

  private static final String PLAYLIST_JSON = // language=JSON
    """
    {
      "name": "Tom Waits Special",
      "author": "me",
      "tracks": [
        { "no": 1, "name": "I Never Talk to Strangers", "stars":4 , "averageStars": 3.9 },
        { "no": 2, "name": "Cold cold Ground", "stars":5, "averageStars": 4.7 }
      ]
    }""";

  @Test
  void objectRoot() {
    Function<Track, String> describe = track -> //
        String.format(
                "%d. %s %s (%.1f)",
                track.no(), track.name(), "*".repeat(track.stars()), track.averageStars());

    Playlist list = JsonStream.ofRoot(Playlist.class, asJsonInput(PLAYLIST_JSON));

    assertEquals("me", list.author());
    assertEquals("Tom Waits Special", list.name());
    assertEquals(
        List.of("1. I Never Talk to Strangers **** (3.9)", "2. Cold cold Ground ***** (4.7)"),
        list.tracks().map(describe).collect(toList()));
  }

  @Test
  void parseException() {
    String json = // language=JSON
        """
    [
				{"no":1, "name": "Earth Died Screaming"},
				null
		]""";

    Stream<Track> items = JsonStream.of(Track.class, asJsonInput(json));

    JsonFormatException ex =
        assertThrows(JsonFormatException.class, () -> items.forEach(Track::name));
    assertEquals(
        """
				Expected one of `]`,`{` but found: `n`
				at: [... <1>
				<stream position>""",
        ex.getMessage());
  }

  @Test
  void illegalParentProxyCallIsDetected() {
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
    Album album = JsonStream.ofRoot(Album.class, asJsonInput(json));

    assertEquals(Genre.Jazz, album.genre());

    Iterator<Track> tracks = album.tracks();
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> tracks.forEachRemaining(track -> track.name().concat(album.artist())));
    assertEquals(
        """
			Parent proxy called out of order
			at: {
				"name": Bone Machine,
				"artist": Tom Waits,
				"genre": Jazz,
				"tracks": [... <0>
				{
					"name": Earth Died Screaming,
				}
			<stream position>""",
        ex.getMessage());
  }

  @Test
  void streamRoot() {
    String json = // language=JSON
    """
    [
				{"no":1, "name": "Earth Died Screaming"},
				{"no":2, "name": "Dirt in the Ground"}
		]""";
    Stream<Track> items = JsonStream.of(Track.class, asJsonInput(json));

    assertEquals(
        List.of("1. Earth Died Screaming", "2. Dirt in the Ground"),
        items.map(track -> track.no() + ". " + track.name()).collect(toList()));
  }

  @Test
  void consumerRoot() {
    Playlist list = JsonStream.ofRoot(Playlist.class, asJsonInput(PLAYLIST_JSON));

    assertEquals("me", list.author());
    assertEquals("Tom Waits Special", list.name());
    List<String> actual = new ArrayList<>();
    list.tracks(
        track -> actual.add(track.no() + ". " + track.name() + " " + ("*".repeat(track.stars()))));
    assertEquals(List.of("1. I Never Talk to Strangers ****", "2. Cold cold Ground *****"), actual);
  }

  @Test
  void proxyToStringShowsParseProgress() {
    Playlist list = JsonStream.ofRoot(Playlist.class, asJsonInput(PLAYLIST_JSON));
    assertEquals("""
    		<stream position>""", list.toString());

    assertNotNull(list.name());

    assertEquals(
"""
    {
    	"name": Tom Waits Special,
    	"author": me,
    <tracks?><stream position>""",
        list.toString());
  }

  @Test
  void proxyHashCodeIsAlwaysSame() {
    Playlist list = JsonStream.ofRoot(Playlist.class, asJsonInput(PLAYLIST_JSON));
    assertEquals(-1, list.hashCode());
  }

  @Test
  void proxyEqualsIsAlwaysFalse() {
    Playlist list = JsonStream.ofRoot(Playlist.class, asJsonInput(PLAYLIST_JSON));
    assertNotEquals(list, list);
    assertNotEquals(list, list.tracks().findAny().orElse(null));
  }

  private static IntSupplier asJsonInput(String json) {
    return JsonStream.from(new StringReader(json.replace('\'', '"')));
  }
}
