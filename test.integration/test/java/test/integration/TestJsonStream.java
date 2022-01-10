package test.integration;

import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonMember;
import se.jbee.json.stream.JsonStream;

import java.io.StringReader;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestJsonStream {

	public interface Root {
		int hello();

		String name(String defaultValue);

		Stream<Element> elements();

		Stream<Other> items();
	}

	public interface Element {

		@JsonMember(key = true)
		String key();

		String id();

		int age();

		Stream<Element> children();
	}

	public interface Other {
		boolean flag();

		String name();
	}

	@Test
	void objectRoot() {
		IntSupplier parser = createParser("{" +
				"'hello':42," +
				"'name':null," +
				"'elements':[{'id':'1','age':13},{'id':'2','age':45, 'children':{'key':{'id':'x','age':66}}]," +
				"'items':[{'name':'itemA','flag':true}]" +
				"}");
		Root root = JsonStream.from(Root.class, parser);

		assertEquals(42, root.hello());
		assertEquals("test", root.name("test"));
		root.elements().forEachOrdered(e -> {
			System.out.println(e.id() +"/"+ e.age());
			e.children().forEachOrdered(c -> System.out.println(c.id() +"/"+ c.age()+"/"+c.key()));
		});
		root.items().forEachOrdered(item -> {
			System.out.println(item.flag() +"/"+ item.name());
		});
	}

	@Test
	void streamRoot() {
		IntSupplier parser = createParser("[{'flag':true, 'name':'A'},{'name':'B'}]");
		Stream<Other> items = JsonStream.of(Other.class, parser);
		items.forEach(item ->  System.out.println(item.name()+" "+item.flag()));
	}


	private static IntSupplier createParser(String json) {
		return JsonStream.readFrom(new StringReader(json.replace('\'', '"')));
	}
}
