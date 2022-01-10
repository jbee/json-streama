open module test.integration {

	requires java.logging;
	requires org.junit.jupiter;
	requires static org.junit.platform.console; // <- launches test modules
	requires static org.junit.platform.jfr; // <- flight-recording support

	requires se.jbee.json.stream;
}
