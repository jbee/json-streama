package test.integration;

import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonFormatException;
import se.jbee.json.stream.JsonStream;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestJsonStream {

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

		Iterator<Track> tracks();
	}

	private static final String PLAYLIST_JSON = """
			{
				'name': 'Tom Waits Special',
				'author': 'me',
				'tracks': [
					{ 'no': 1, 'name': 'I Never Talk to Strangers', 'stars':4 },
					{ 'no': 2, 'name': 'Cold cold Ground', 'stars':5 }
				]
			}
			""";

	@Test
	void objectRoot() {
		Playlist list = JsonStream.ofRoot(Playlist.class, createParser(PLAYLIST_JSON));

		assertEquals("me", list.author());
		assertEquals("Tom Waits Special", list.name());
		assertEquals(List.of("1. I Never Talk to Strangers ****", "2. Cold cold Ground *****"),
				list.tracks().map(track -> track.no()+". "+track.name() +" "+("*".repeat(track.stars()))).collect(toList()));
	}

	@Test
	void parseException() {
		Stream<Track> items = JsonStream.of(Track.class, createParser("""
    [
				{'no':1, 'name': 'Earth Died Screaming'},
				null
		]"""));

		JsonFormatException ex = assertThrows(JsonFormatException.class,
				() -> items.forEach(Track::name));
		assertEquals("""
				Expected one of `]`,`{` but found: `n`
				at: [... <1>
				<stream position>""", ex.getMessage());
	}

	@Test
	void illegalParentProxyCallIsDetected() {
		Album album = JsonStream.ofRoot(Album.class, createParser("""
				{
					'name': 'Bone Machine',
					'artist': 'Tom Waits',
					'tracks': { 
						'1': { 'name': 'Earth Died Screaming'},
						'2': { 'name': 'Dirt in the Ground'},
					}
				}
				"""));
		Iterator<Track> tracks = album.tracks();
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> tracks.forEachRemaining(track -> track.name().concat(album.artist())));
		assertEquals("""
			Parent proxy called out of order
			at: {
				"name": Bone Machine,
				"artist": Tom Waits,
				"tracks": [... <0>
				{
					"name": Earth Died Screaming,
				}
			<stream position>""", ex.getMessage());
	}

	@Test
	void streamRoot() {
		Stream<Track> items = JsonStream.of(Track.class, createParser("""
    [
				{'no':1, 'name': 'Earth Died Screaming'},
				{'no':2, 'name': 'Dirt in the Ground'}
		]"""));
		assertEquals(List.of("1. Earth Died Screaming", "2. Dirt in the Ground"),
				items.map(track -> track.no()+". "+track.name()).collect(toList()));
	}

	@Test
	void consumerRoot() {
		Playlist list = JsonStream.ofRoot(Playlist.class, createParser(PLAYLIST_JSON));

		assertEquals("me", list.author());
		assertEquals("Tom Waits Special", list.name());
		List<String> actual = new ArrayList<>();
		list.tracks(track -> actual.add(track.no()+". "+track.name() +" "+("*".repeat(track.stars()))));
		assertEquals(List.of("1. I Never Talk to Strangers ****", "2. Cold cold Ground *****"), actual);
	}

	private static IntSupplier createParser(String json) {
		return JsonStream.from(new StringReader(json.replace('\'', '"')));
	}
}
