package test.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonMember;
import se.jbee.json.stream.JsonStream;

import java.io.StringBufferInputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestJsonStream {

	public interface Root {
		int hello();

		String name();

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
	void test() {
		String json = "{'hello':42,'name':'test','elements':[{'id':'1','age':13},{'id':'2','age':45, 'children':{'key':{'id':'x','age':66}}],'items':[{'name':'itemA','flag':true}]}".replace('\'', '"');

		Root root = JsonStream.of(Root.class, new StringBufferInputStream(json));

		assertEquals(42, root.hello());
		assertEquals("test", root.name());
		root.elements().forEachOrdered(e -> {
			System.out.println(e.id() +"/"+ e.age());
			e.children().forEachOrdered(c -> System.out.println(c.id() +"/"+ c.age()+"/"+c.key()));
		});
		root.items().forEachOrdered(item -> {
			System.out.println(item.flag() +"/"+ item.name());
		});
	}
}
