package se.jbee.json.stream;

import static java.lang.Character.isWhitespace;
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

  int readNodeAutodetect(Consumer<Serializable> setter) {
    int cp = readCharSkipWhitespace();
    switch (cp) {
      case '{':
        setter.accept(readMap());
        break;
      case '[':
        setter.accept(readArray());
        break;
      case 't':
        readSkip(3);
        setter.accept(true);
        break;
      case 'f':
        readSkip(4);
        setter.accept(false);
        break;
      case 'n':
        readSkip(3);
        setter.accept(null);
        break;
      case '"':
        setter.accept(readString());
        break;
      case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-':
        return readNumber(cp, setter);
      default:
        throw formatException(
            cp, '{', '[', '"', 'n', 't', 'f', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '-');
    }
    return readCharSkipWhitespace();
  }

  /**
   * @return the code point after the node that is skipped, most likely a comma or closing array or
   *     object.
   */
  int skipNodeAutodetect() {
    return -1; // TODO
  }

  private static boolean isDigit(int cp) {
    return cp >= '0' && cp <= '9';
  }

  private int readNumber(int cp0, Consumer<Serializable> setter) {
    StringBuilder n = new StringBuilder();
    n.append((char) cp0);
    int cp = read.getAsInt();
    cp = readDigits(n, cp);
    if (cp == '.') {
      n.append('.');
      cp = readDigits(n, read.getAsInt());
    }
    if (cp == 'e' || cp == 'E') {
      n.append('e');
      cp = read.getAsInt();
      if (cp == '+' || cp == '-') {
        n.append((char) cp);
        cp = read.getAsInt();
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
      cp = read.getAsInt();
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
      cp = readNodeAutodetect(res::add);
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
      cp = readNodeAutodetect(value -> res.put(key, value));
    }
    return res;
  }

  String readString() {
    StringBuilder str = new StringBuilder();
    int cp = read.getAsInt();
    while (cp != -1) {
      if (cp == '"') {
        // found the end (if escaped we would have hopped over)
        return str.toString();
      }
      if (cp == '\\') {
        cp = read.getAsInt();
        switch (cp) {
          case 'u' -> // unicode uXXXX
          {
            int[] code = {read.getAsInt(), read.getAsInt(), read.getAsInt(), read.getAsInt()};
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
      cp = read.getAsInt();
    }
    throw formatException(-1, '"');
  }

  private void readSkip(int n) {
    for (int i = 0; i < n; i++) if (read.getAsInt() == -1) throw formatException(-1);
  }

  void readCharSkipWhitespaceAndExpect(char expected) {
    expect(expected, readCharSkipWhitespace());
  }

  int readCharSkipWhitespace() {
    int c = read.getAsInt();
    while (c != -1 && Character.isWhitespace(c)) c = read.getAsInt();
    if (c == -1) throw formatException(-1);
    return c;
  }

  private void expect(char expected, int cp) {
    if (cp != expected) throw formatException(cp, expected);
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
