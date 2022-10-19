package se.jbee.json.stream;

import java.util.function.IntSupplier;
import java.util.stream.Stream;

/**
 * Only reason to use the factory is to share and reuse the internal state created for each {@link
 * JsonToJava} mapping.
 */
public interface JsonStreamFactory {

  <T> T ofRoot(Class<T> objType, IntSupplier in);

  <T> Stream<T> of(Class<T> streamType, IntSupplier in);
}
