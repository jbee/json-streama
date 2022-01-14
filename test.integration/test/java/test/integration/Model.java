package test.integration;

import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Defines a target the test use to map JSON input to. */
interface Model {

  enum Genre {
    Jazz,
    Pop
  }

  interface Playlist {
    String name();

    String author();

    Stream<Track> tracks();

    void tracks(Consumer<Track> consumer);
  }

  interface Track {

    int no();

    String name();

    String artist();

    long duration();

    int stars();

    float averageStars();
  }

  interface Album {

    String name();

    default String title() {
      return name();
    }

    String artist();

    Genre genre();

    Iterator<Track> tracks();
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
