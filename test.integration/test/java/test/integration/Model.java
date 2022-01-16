package test.integration;

import java.util.function.Consumer;
import java.util.stream.Stream;
import se.jbee.json.stream.JsonProperty;

/** Defines a target the test use to map JSON input to. */
interface Model {

  enum Genre {
    Jazz,
    Pop
  }

  interface Discography {

    String artist();

    Stream<Album> albums();

    void singles(Consumer<Single> eachSingle);
  }

  interface Playlist {
    String name();

    String author();

    Stream<Track> tracks();

    void tracks(Consumer<Track> eachTrack);
  }

  interface Track {

    @JsonProperty(key = true)
    int no();

    String name();

    String artist();

    long duration();

    int stars();

    float averageStars();
  }

  interface Single {
    String name();

    Stream<Track> tracks();
  }

  interface Album {

    String name();

    default String title() {
      return name();
    }

    String artist();

    Genre genre();

    Stream<Track> tracks();
  }

  String PLAYLIST_JSON = // language=JSON
      """
    {
      "name": "Tom Waits Special",
      "author": "me",
      "tracks": [
        { "no": 1, "name": "I Never Talk to Strangers", "stars":4 , "averageStars": 3.9 },
        { "no": 2, "name": "Cold cold Ground", "stars":5, "averageStars": 4.7 }
      ]
    }""";
}
