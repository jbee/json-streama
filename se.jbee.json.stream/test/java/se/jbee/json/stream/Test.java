package se.jbee.json.stream;

import java.io.StringBufferInputStream;
import java.util.stream.Stream;

public class Test {

	public interface Root {
		int hello();

		String name();

		Stream<Element> elements();

		Stream<Other> items();
	}

	public interface Element {

		String id();

		int age();

		Stream<Element> children();
	}

	public interface Other {
		boolean flag();

		String name();
	}

	public static void main(String[] args) {
		Root json = JsonStream.of(Root.class, new StringBufferInputStream("{'hello':42,'name':'test','elements':[{'id':'1','age':13},{'id':'2','age':45, 'children':[{'id':'x','age':66}]],'items':[{'name':'itemA','flag':true}]}".replace('\'', '"')));
		System.out.println(json.hello());
		System.out.println(json.name());
		json.elements().forEachOrdered(e -> {
			System.out.println(e.id() +"/"+ e.age());
			e.children().forEachOrdered(c -> System.out.println(c.id() +"/"+ c.age()));
		});
		json.items().forEachOrdered(item -> {
			System.out.println(item.flag() +"/"+ item.name());
		});
	}
}
