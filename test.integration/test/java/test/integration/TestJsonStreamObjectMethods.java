package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static test.integration.Model.PLAYLIST_JSON;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonStream;
import test.integration.Model.Discography;
import test.integration.Model.Playlist;

/**
 * Tests to check the correct behaviour of {@link java.lang.reflect.Proxy} methods declared by
 * {@link Object}.
 */
class TestJsonStreamObjectMethods {

  @Test
  void toStringShowsParseProgress() {
    Playlist list = JsonStream.ofRoot(Playlist.class, JsonInputStream.of(PLAYLIST_JSON));
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
  void toStringShowsItemCountOfAlreadyProcessedContinuations() {
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

    Discography discography = JsonStream.ofRoot(Discography.class, JsonInputStream.of(json));
    assertEquals("Tom Waits", discography.artist());
    AtomicInteger singles = new AtomicInteger();
    discography.singles().forEach(
        single -> {
          single.name(); // need to actually consume a single
          singles.incrementAndGet();
        });
    assertEquals(1, singles.get());
    assertNotNull(discography.albums(), "need to access album to forwards stream beyond singles");
    assertEquals(
        """
    {
    	"artist": Tom Waits,
    	"singles": [<1>],
    	"albums": [... <-1>
    	<stream position>""",
        discography.toString());
  }

  @Test
  void hashCodeIsAlwaysSame() {
    Playlist list = JsonStream.ofRoot(Playlist.class, JsonInputStream.of(PLAYLIST_JSON));
    assertEquals(-1, list.hashCode());
  }

  @Test
  void equalsIsAlwaysFalse() {
    Playlist list = JsonStream.ofRoot(Playlist.class, JsonInputStream.of(PLAYLIST_JSON));
    assertNotEquals(list, list);
    assertNotEquals(list, list.tracks().findAny().orElse(null));
  }
}
