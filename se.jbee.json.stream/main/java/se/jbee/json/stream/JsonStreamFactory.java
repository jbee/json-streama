package se.jbee.json.stream;

import java.util.function.IntSupplier;
import java.util.stream.Stream;

/**
 * Only reason to use the factory is to share and reuse the internal state created for each {@link JsonToJava} mapping.
 */
public interface JsonStreamFactory {

  static JsonStreamFactory newFactory(JsonToJava toJava) {
    //TODO move to inter type of JsonStream (to hide internal state used by it)

    return null;
  }

  <T> T ofRoot(Class<T> objType, IntSupplier in);

  <T> Stream<T> of(Class<T> streamType, IntSupplier in);
}
