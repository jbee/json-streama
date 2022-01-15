package test.integration;

import static org.junit.jupiter.api.Assertions.*;
import static test.integration.Model.Genre.*;
import static test.integration.Utils.asJsonInput;

import java.util.Iterator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonFormatException;
import se.jbee.json.stream.JsonStream;
import test.integration.Model.Album;
import test.integration.Model.Discography;
import test.integration.Model.Track;

/**
 * Tests the correctness of the implementation when it comes to throwing exceptions for detected
 * errors.
 *
 * <p>This either means the JSON input is malformed or is valid JSON but not valid for the expected
 * format.
 *
 * <p>Examples of invalid format (that generally would be valid JSON):
 *
 * <ul>
 *   <li>{@code null} as a stream value
 *   <li>stream members occurring in a different order than they are processed
 * </ul>
 *
 * A third type of error is a programming error when using the {@link java.lang.reflect.Proxy} API.
 * Parent proxies cannot be used while processing their child stream members.
 */
class TestJsonStreamExceptions {

  @Test
  void nullValueInAStreamIsFormatException() {
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
  void providingStreamMembersOutOfAccessOrderCausesIllegalStateException() {
    String json = // language=JSON
        """
    {
      "artist": "Tom Waits",
      "singles": [{
        "name": "Downtown Train"
      }],
      "albums": [{
        "name": "Rain Dogs"
      }]
    }""";

    Discography discography = JsonStream.ofRoot(Discography.class, asJsonInput(json));
    assertEquals("Tom Waits", discography.artist());
    // this might be surprising at first, we do not count any albums
    // the reason is that the next stream member we found is 'singles'
    // so we assume albums were not present, hence 0 in count
    assertEquals(0, discography.albums().count());
    // processing the singles is fine, we now expected this
    discography.singles(single -> assertNotNull(single.name()));
    // but should we now try to process albums as they are after the singles in the stream
    // we get an error as we already processed albums before
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> discography.albums().count());
    assertEquals("Continuation `albums` was already accessed before.", ex.getMessage());
  }

  @Test
  void accessingParentProxiesWhileInStreamCausesIllegalStateException() {
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

    assertEquals(Jazz, album.genre());

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
}
