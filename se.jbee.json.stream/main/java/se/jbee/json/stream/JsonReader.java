package se.jbee.json.stream;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static java.lang.Character.isWhitespace;
import static java.lang.Character.toChars;
import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.joining;

record JsonReader(IntSupplier read, Supplier<String> printPosition) {

	int readAutodetect(Consumer<Serializable> setter) {
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
				throw formatException(cp, '{', '[', '"', 'n', 't', 'f', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-');
		}
		return readCharSkipWhitespace();
	}

	private static boolean isDigit(int cp) {
		return cp >= '0' && cp <= '9';
	}

	private int readNumber(int cp0, Consumer<Serializable> setter) {
		StringBuilder n = new StringBuilder();
		n.append((char) cp0);
			int cp = read.getAsInt();
			cp = readDigits(n, cp);
			if (cp == '.') cp = readDigits(n, cp);
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

	private ArrayList<Serializable> readArray() {
		//TODO
		return new ArrayList<>();
	}

	private LinkedHashMap<String, Serializable> readMap() {
		//TODO
		return new LinkedHashMap<>();
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
							str.append(toChars(parseInt(new String(new int[]{read.getAsInt(), read.getAsInt(), read.getAsInt(), read.getAsInt()}, 0, 4), 16)));
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
		for (int i = 0; i < n; i++)
			if (read.getAsInt() == -1) throw formatException(-1);
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
		String expectedText = expected.length == 0
				? "more input"
				: "one of "+new String(expected).chars().mapToObj(c -> "`" + (char)c + "`").collect(joining(","));
		return new JsonFormatException("Expected " + expectedText + " but found: " + foundText + "\nat: " + printPosition.get());
	}
}
