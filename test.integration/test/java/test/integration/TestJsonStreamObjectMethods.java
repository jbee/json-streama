package test.integration;

import static org.junit.jupiter.api.Assertions.*;
import static test.integration.Model.PLAYLIST_JSON;
import static test.integration.Utils.asJsonInput;

import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonStream;
import test.integration.Model.Playlist;

/**
 * Tests to check the correct behaviour of {@link java.lang.reflect.Proxy} methods declared by
 * {@link Object}.
 */
class TestJsonStreamObjectMethods {

  @Test
  void toStringShowsParseProgress() {
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
  void hashCodeIsAlwaysSame() {
    Playlist list = JsonStream.ofRoot(Playlist.class, asJsonInput(PLAYLIST_JSON));
    assertEquals(-1, list.hashCode());
  }

  @Test
  void equalsIsAlwaysFalse() {
    Playlist list = JsonStream.ofRoot(Playlist.class, asJsonInput(PLAYLIST_JSON));
    assertNotEquals(list, list);
    assertNotEquals(list, list.tracks().findAny().orElse(null));
  }
}
