package se.jbee.json.stream;

import static java.lang.Character.toChars;
import static java.lang.Integer.parseInt;
import static se.jbee.json.stream.JsonFormatException.unexpectedInputCharacter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A code point based JSON input reader.
 *
 * <p>Characters are supplied by the {@link JsonInputStream}. As usual the end of input is marked by
 * returning {@code -1}.
 *
 * <p>The reader supports mapping JSON to {@link String}, {@link Number} or {@link Boolean} as well
 * as {@link java.util.List}s or {@link java.util.Map}s.
 *
 * <p>In addition, input can also be skipped, that means consumed without mapping it to anything.
 *
 * @author Jan Bernitt
 * @since 1.0
 */
record JsonParser(JsonInputStream in, Supplier<String> printPosition) {

  private static final char[] NODE_STARTING_CHARS = {
    '{', // object
    '[', // array
    '"', // string
    'n', // null
    't', 'f', // boolean
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-'
  };

  static Serializable parse(String json) {
    AtomicReference<Serializable> value = new AtomicReference<>();
    new JsonParser(JsonInputStream.of(json + ","), () -> json).readNodeDetect(value::set);
    return value.get();
  }

  /*
  API
   */

  int peek() {
    return in.peek();
  }

  int readNodeDetect(Consumer<Serializable> setter) {
    return readNodeDetect(setter, false);
  }

  /**
   * A node value is:
   *
   * <dl>
   *   <dt>JSON null node
   *   <dd>{@code null} reference
   *   <dt>JSON string node
   *   <dd>{@link String}
   *   <dt>JSON number node
   *   <dd>A suitable subtype of {@link Number}; {@link Integer}, {@link Long} or {@link Double}
   *   <dt>JSON boolean node
   *   <dd>{@link Boolean}
   *   <dt>JSON array node
   *   <dd>{@link ArrayList} of any of the other returned types based on what the list elements were
   *   <dt>JSON object node
   *   <dd>{@link LinkedHashMap} of any of the other returned types based on what the object member
   *       values were
   * </dl>
   *
   * @param setter consumes the parsed node value
   * @return the next non whitespace code point in the stream after the value read. A caller needs
   *     to consider this to correctly continue processing the stream.
   */
  int readNodeDetect(Consumer<Serializable> setter, boolean allowArrayClose) {
    int cp = readCharSkipWhitespace();
    switch (cp) {
      case '{':
        setter.accept(readMap());
        break;
      case '[':
        setter.accept(readArray());
        break;
      case 't':
        skipCodePoints(3);
        setter.accept(true);
        break;
      case 'f':
        skipCodePoints(4);
        setter.accept(false);
        break;
      case 'n':
        skipCodePoints(3);
        setter.accept(null);
        break;
      case '"':
        setter.accept(readString());
        break;
      case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-':
        return readNumber(cp, setter);
      case ']':
        if (allowArrayClose) return ']';
        // intentional fall-through
      default:
        throw formatException(cp, NODE_STARTING_CHARS);
    }
    return readCharSkipWhitespace();
  }

  /**
   * @return the code point after the node that is skipped, most likely a comma or closing array or
   *     object or end of the stream
   */
  int skipNodeDetect() {
    int cp = readCharSkipWhitespace();
    return switch (cp) {
      case '{' -> skipObject();
      case '[' -> skipArray();
      case ']' -> cp; // empty array, this cp needs to be recognised by the caller
      case '"' -> skipString();
      case 't', 'f' -> skipBoolean();
      case 'n' -> skipNull();
      case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-' -> skipNumber();
      default -> throw formatException(cp, NODE_STARTING_CHARS);
    };
  }

  /**
   * Assumes the opening double-quotes has been consumed already.
   *
   * <p>After this method the closing double-quotes is the last already consumed character.
   *
   * @return the JSON string node as Java {@link String}
   */
  String readString() {
    StringBuilder str = new StringBuilder();
    int cp = nextAnyCP();
    while (cp != -1) {
      if (cp == '"') {
        // found the end (if escaped we would have hopped over)
        return str.toString();
      }
      if (cp == '\\') {
        cp = nextAsciiCP();
        switch (cp) {
          case 'u' -> // unicode uXXXX
          {
            int[] code = {nextAsciiCP(), nextAsciiCP(), nextAsciiCP(), nextAsciiCP()};
            str.append(toChars(parseInt(new String(code, 0, 4), 16)));
          }
          case '\\' -> str.append('\\');
          case '/' -> str.append('/');
          case 'b' -> str.append('\b');
          case 'f' -> str.append('\f');
          case 'n' -> str.append('\n');
          case 'r' -> str.append('\r');
          case 't' -> str.append('\t');
          case '"' -> str.append('"');
          default -> throw formatException(cp, 'u', '\\', 'b', 'f', 'n', 'r', 't', '"');
        }
      } else {
        str.appendCodePoint(cp);
      }
      cp = nextAnyCP();
    }
    throw formatException(-1, '"');
  }

  void readCharSkipWhitespace(char expected) {
    int cp = readCharSkipWhitespace();
    if (cp != expected) throw formatException(cp, expected);
  }

  /**
   * This expects to find a non whitespace character in the input, otherwise this is considered an
   * error.
   *
   * @return the next code point in the input that is not JSON whitespace
   */
  int readCharSkipWhitespace() {
    int cp = nextAsciiCP();
    while (cp != -1 && isWhitespace(cp)) cp = nextAsciiCP();
    if (cp == -1) throw formatException(-1);
    return cp;
  }

  /*
  Implementation
   */

  /** In JSON only ASCII digits are allowed */
  private static boolean isDigit(int cp) {
    return cp >= '0' && cp <= '9';
  }

  /** In JSON whitespace is defined as the below 4 ASCII characters so this is what we accept */
  private static boolean isWhitespace(int cp) {
    return cp == ' ' || cp == '\t' || cp == '\r' || cp == '\n';
  }

  private int readNumber(int cp0, Consumer<Serializable> setter) {
    StringBuilder n = new StringBuilder();
    n.append((char) cp0);
    int cp = nextAsciiCP();
    cp = readDigits(n, cp);
    if (cp == '.') {
      n.append('.');
      cp = readDigits(n, nextAsciiCP());
    }
    if (cp == 'e' || cp == 'E') {
      n.append('e');
      cp = nextAsciiCP();
      if (cp == '+' || cp == '-') {
        n.append((char) cp);
        cp = nextAsciiCP();
      }
      cp = readDigits(n, cp);
    }
    setter.accept(parseNumber(n.toString()));
    return isWhitespace(cp) ? readCharSkipWhitespace() : cp;
  }

  static Number parseNumber(String value) {
    // TODO handle big int/big decimal
    double number = Double.parseDouble(value);
    if (number % 1 == 0d) {
      long asLong = (long) number;
      if (asLong < Integer.MAX_VALUE && asLong > Integer.MIN_VALUE) {
        return (int) asLong;
      } else return asLong;
    } else return number;
  }

  private int readDigits(StringBuilder n, int cp0) {
    int cp = cp0;
    while (isDigit(cp)) {
      n.append((char) cp);
      cp = nextAsciiCP();
    }
    return cp;
  }

  /**
   * Assumes the opening [ is already consumed.
   *
   * <p>After the parsing the closing ] is the last consumed character.
   *
   * @return list of directly converted values
   */
  private ArrayList<Serializable> readArray() {
    ArrayList<Serializable> res = new ArrayList<>();
    int cp = ',';
    while (cp != ']') {
      if (cp != ',') throw formatException(cp, ',', ']');
      cp = readNodeDetect(res::add, true);
    }
    return res;
  }

  /**
   * Assumes the opening { is already consumed.
   *
   * <p>After the parsing rhe closing } is the last consumed character.
   *
   * @return map for the JSON object
   */
  private LinkedHashMap<String, Serializable> readMap() {
    LinkedHashMap<String, Serializable> res = new LinkedHashMap<>();
    int cp = readCharSkipWhitespace();
    while (cp != '}') {
      String key = readString();
      readCharSkipWhitespace(':');
      cp = readNodeDetect(value -> res.put(key, value));
      if (cp != ',' && cp != '}') throw formatException(cp, ',', '}');
      if (cp == ',') readCharSkipWhitespace('"');
      // technically cp got not updated to latest " but we want compare , or } for loop condition
    }
    return res;
  }

  private int nextAsciiCP() {
    int c = in.read();
    if (c > 127 || c < 0)
      throw unexpectedInputCharacter(c, printPosition.get(), "A ASCII range character");
    return c;
  }

  private int nextAnyCP() {
    return in.readCodePoint();
  }

  public void skipCodePoints(int n) {
    for (int i = 0; i < n; i++) if (nextAsciiCP() == -1) throw formatException(-1);
  }

  private int skipNumber() {
    // first digit or - has been consumed
    int cp = skipDigits();
    if (cp == '.') cp = skipDigits();
    if (cp == 'e' || cp == 'E') {
      nextAsciiCP(); // +/-/digit
      cp = skipDigits();
    }
    return cp;
  }

  private int skipDigits() {
    int cp = nextAsciiCP();
    while (isDigit(cp)) cp = nextAsciiCP();
    return cp;
  }

  private int skipNull() {
    // n has been consumed
    skipCodePoints(3);
    return readCharSkipWhitespace();
  }

  private int skipBoolean() {
    // t/f has been consumed
    skipCodePoints(nextAsciiCP() == 'r' ? 2 : 3);
    return readCharSkipWhitespace();
  }

  private int skipString() {
    // " has been consumed
    int cp = nextAnyCP();
    while (cp != '"') {
      if (cp == '\\') {
        cp = nextAsciiCP();
        // hop over escaped char or unicode
        if (cp == 'u') skipCodePoints(4);
      }
      cp = nextAnyCP();
    }
    return readCharSkipWhitespace();
  }

  private int skipArray() {
    // [ has been consumed
    int cp = ',';
    while (cp != ']') {
      if (cp != ',') throw formatException(cp, ',', ']');
      cp = skipNodeDetect();
    }
    return readCharSkipWhitespace();
  }

  private int skipObject() {
    // { has been consumed
    int cp = ',';
    while (cp != '}') {
      if (cp != ',') throw formatException(cp, ',', '}');
      cp = readCharSkipWhitespace();
      if (cp == '"') {
        cp = skipString();
        if (cp != ':') throw formatException(cp, ':');
        cp = skipNodeDetect();
      } else if (cp != '}') throw formatException(cp, '"', '}');
    }
    return readCharSkipWhitespace();
  }

  private JsonFormatException formatException(int found, char... expected) {
    return unexpectedInputCharacter(found, printPosition.get(), expected);
  }
}
