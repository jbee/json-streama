package test.integration;

import java.io.StringReader;
import java.util.function.IntSupplier;
import se.jbee.json.stream.JsonStream;

final class Utils {

  private Utils() {
    throw new UnsupportedOperationException("util");
  }

  static IntSupplier asJsonInput(String json) {
    return JsonStream.asIntSupplier(new StringReader(json));
  }
}
