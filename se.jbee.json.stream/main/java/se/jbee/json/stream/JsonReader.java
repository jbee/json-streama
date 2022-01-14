package se.jbee.json.stream;

import static java.lang.Character.toChars;
import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.joining;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

record JsonReader(IntSupplier read, Supplier<String> printPosition) {

  private static final char[] NODE_STARTING_CHARS = {
    '{', // object
    '[', // array
    '"', // string
    'n', // null
    't',
    'f', // boolean
    '0',
    '1',
    '2',
    '3',
    '4',
    '5',
    '6',
    '7',
    '8',
    '9',
    '-' // number
  };

  int readNodeDetect(Consumer<Serializable> setter) {
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
    int cp = nextCodePoint();
    cp = readDigits(n, cp);
    if (cp == '.') {
      n.append('.');
      cp = readDigits(n, nextCodePoint());
    }
    if (cp == 'e' || cp == 'E') {
      n.append('e');
      cp = nextCodePoint();
      if (cp == '+' || cp == '-') {
        n.append((char) cp);
        cp = nextCodePoint();
      }
      cp = readDigits(n, cp);
    }
    double number = Double.parseDouble(n.toString());
    if (number % 1 == 0d) {
      long asLong = (long) number;
      if (asLong < Integer.MAX_VALUE && asLong > Integer.MIN_VALUE) {
        setter.accept((int) asLong);
      } else setter.accept(asLong);
    } else setter.accept(number);
    return isWhitespace(cp) ? readCharSkipWhitespace() : cp;
  }

  private int readDigits(StringBuilder n, int cp0) {
    int cp = cp0;
    while (isDigit(cp)) {
      n.append((char) cp);
      cp = nextCodePoint();
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
      cp = readNodeDetect(res::add);
    }
    return res;
  }

  private LinkedHashMap<String, Serializable> readMap() {
    LinkedHashMap<String, Serializable> res = new LinkedHashMap<>();
    int cp = ',';
    while (cp != '}') {
      if (cp != ',') throw formatException(cp, ',', '}');
      readCharSkipWhitespaceAndExpect('"');
      String key = readString();
      readCharSkipWhitespaceAndExpect(':');
      cp = readNodeDetect(value -> res.put(key, value));
    }
    return res;
  }

  String readString() {
    StringBuilder str = new StringBuilder();
    int cp = nextCodePoint();
    while (cp != -1) {
      if (cp == '"') {
        // found the end (if escaped we would have hopped over)
        return str.toString();
      }
      if (cp == '\\') {
        cp = nextCodePoint();
        switch (cp) {
          case 'u' -> // unicode uXXXX
          {
            int[] code = {nextCodePoint(), nextCodePoint(), nextCodePoint(), nextCodePoint()};
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
      cp = nextCodePoint();
    }
    throw formatException(-1, '"');
  }

  private int nextCodePoint() {
    return read.getAsInt();
  }

  void readCharSkipWhitespaceAndExpect(char expected) {
    expect(expected, readCharSkipWhitespace());
  }

  int readCharSkipWhitespace() {
    int cp = nextCodePoint();
    while (cp != -1 && isWhitespace(cp)) cp = nextCodePoint();
    if (cp == -1) throw formatException(-1);
    return cp;
  }

  private void expect(char expected, int cp) {
    if (cp != expected) throw formatException(cp, expected);
  }

  private void skipCodePoints(int n) {
    for (int i = 0; i < n; i++) if (nextCodePoint() == -1) throw formatException(-1);
  }

  private int skipNumber() {
    // first digit or - has been consumed
    int cp = skipDigits();
    if (cp == '.') cp = skipDigits();
    if (cp == 'e' || cp == 'E') {
      nextCodePoint(); // +/-/digit
      cp = skipDigits();
    }
    return cp;
  }

  private int skipDigits() {
    int cp = nextCodePoint();
    while (isDigit(cp)) cp = nextCodePoint();
    return cp;
  }

  private int skipNull() {
    // n has been consumed
    skipCodePoints(3);
    return readCharSkipWhitespace();
  }

  private int skipBoolean() {
    // t/f has been consumed
    skipCodePoints(nextCodePoint() == 'r' ? 2 : 3);
    return readCharSkipWhitespace();
  }

  private int skipString() {
    // " has been consumed
    int cp = nextCodePoint();
    while (cp != '"') {
      if (cp == '\\') {
        cp = nextCodePoint();
        // hop over escaped char or unicode
        if (cp == 'u') skipCodePoints(4);
      }
      cp = nextCodePoint();
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

  JsonFormatException formatException(int found, char... expected) {
    String foundText = found == -1 ? "end of input" : "`" + Character.toString(found) + "`";
    String expectedText =
        expected.length == 0 ? "more input" : "one of " + toExpectedList(expected);
    return new JsonFormatException(
        "Expected " + expectedText + " but found: " + foundText + "\nat: " + printPosition.get());
  }

  private String toExpectedList(char[] expected) {
    return new String(expected).chars().mapToObj(c -> "`" + (char) c + "`").collect(joining(","));
  }
}
