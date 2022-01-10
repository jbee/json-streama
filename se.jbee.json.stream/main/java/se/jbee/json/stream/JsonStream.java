package se.jbee.json.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.Character.isUpperCase;
import static java.lang.Character.toLowerCase;
import static java.util.Collections.emptyIterator;
import static java.util.Spliterators.spliteratorUnknownSize;
import static se.jbee.json.stream.JsonParser.formatException;

/**
 * Reading complex JSON documents as streams using user defined {@link Proxy} interfaces and {@link Stream}/{@link
 * Iterator}s.
 * <p>
 * (c) 2022
 *
 * @author Jan Bernitt
 */
public final class JsonStream implements InvocationHandler {

	public static IntSupplier readFrom(InputStream in) {
		return () -> {
			try {
				return in.read();
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
	}

	public static IntSupplier readFrom(Reader in) {
		return () -> {
			try {
				return in.read();
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		};
	}

	// this is mainly convention based
	// we assume:
	// - all but Stream/Iterator typed methods are simple nodes of type boolean, number or string
	// - any type that is not recognised is a wrapper on a simple node and we try to find the constructor for the value we actually found
	// - if there are multiple stream children the order we expect is the order they are called or annotated
	// - a "map" (object used as such) is also modelled as a stream of elements which have a key

	// allow void methods with a Consumer<A> parameter to make sequence independent continuations
	// this way the parser can see what member with a isContinuation type comes first and call the consumer for it
	// could also allow Consumer<Stream<X>>

	// When the item type is an interface we know we are doing sub-objects, otherwise we will assume primitive items and use special parsing and streams.

	public static <T> T from(Class<T> rootObjType, IntSupplier in) {
		return from(rootObjType, in, JsonMapping.auto());
	}

	public static <T> T from(Class<T> rootObjType, IntSupplier in, JsonMapping mapping) {
		// if we deal with a root object having members that "continue" the stream
		JsonStream handler = new JsonStream(in, mapping);
		handler.pushFrame(rootObjType);
		return newProxy(rootObjType, handler);
	}

	public static <T> Stream<T> of(Class<T> streamType, IntSupplier in) {
		return of(streamType, in, JsonMapping.auto());
	}

	public static <T> Stream<T> of(Class<T> streamType, IntSupplier in, JsonMapping mapping) {
		// if we deal with a root array or "map" object treated as such
		Member member = new Member(MemberProcessing.STREAM, "", Stream.class, streamType, false, Stream.empty());
		JsonStream handler = new JsonStream(in, mapping);
		handler.pushFrame(streamType);
		@SuppressWarnings("unchecked")
		Stream<T> res = (Stream<T>) handler.yieldContinuation(member, new String[0]);
		return res;
	}

	private <T> void initStreamType(Class<T> type) {
		if (!type.isInterface())
			throw new IllegalArgumentException("Stream must be mapped to an interface type but got: "+type);
		MEMBERS_BY_TYPE.computeIfAbsent(type, key -> {
			Map<String, Member> membersByName = new HashMap<>();
			for (Method m : key.getMethods()) {
				if (!m.isDefault() && !m.isSynthetic() && !Modifier.isStatic(m.getModifiers())) {
					Member member = new Member(m);
					MEMBERS_BY_METHOD.put(m, member);
					String name = member.name();
					if (!membersByName.containsKey(name) || m.getParameterCount() == 0) {
						membersByName.put(name, member);
					}
				}
			}
			return membersByName;
		});
	}

	/**
	 * As all classes whose methods we put in here are interfaces where is no risk of any memory leaks.
	 * This can just be shared in the JVM.
	 */
	private static final Map<Method, Member> MEMBERS_BY_METHOD = new ConcurrentHashMap<>();
	private static final Map<Class<?>, Map<String, Member>> MEMBERS_BY_TYPE = new ConcurrentHashMap<>();

	private static final String OBJECT_KEY = "__KEY__";

	private final JsonParser in;
	private final JsonMapping mapping;
	private final Map<Class<?>, JsonMapper<?>> mappersByToType = new HashMap<>();
	/**
	 * The currently processed frame is always at index 0 - this means the top most frame of the JSON structure is at the end.
	 */
	private final Deque<JsonFrame> stack = new LinkedList<>();

	private JsonStream(IntSupplier in, JsonMapping mapping) {
		this.in = new JsonParser(in);
		this.mapping = mapping;
	}

	private JsonMapper<?> getMapper(Class<?> to) {
		return mappersByToType.computeIfAbsent(to, mapping::mapTo);
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) {
		JsonFrame frame = currentFrame();
		//TODO we could check the proxy type or the method declaring class to see if the call is for the current frame
		readMembersToContinuation(frame);
		if (method.getReturnType().isRecord()) {
			//TODO
		}
		return yieldValue(frame, MEMBERS_BY_METHOD.get(method), args);
	}

	private Object yieldValue(JsonFrame frame, Member member, Object[] args) {
		String name = member.name();
		if (member.isContinuation()) {
			if (!name.equals(frame.encounteredContinuation)) {
				if (frame.hasValue(name))
					throw new IllegalStateException("A continuation member can only be used once");
				// assume the input does not contain the requested member so its value is empty
				frame.markAsConsumed(name);
				return member.isStream() ? Stream.empty() : emptyIterator();
			}
			frame.markAsConsumed(name);
			// what we have is what we now are going to handle so afterwards we could do next one
			frame.encounteredContinuation = null;
			return yieldContinuation(member, args);
		}
		return yieldDirect(member, frame.directValue(name), args);
	}

	private Object yieldDirect(Member member, Object value, Object[] args) {
		if (value == null)
			return member.hasDefault() ? args[0] : member.nullValue();
		Class<?> as = member.type();
		if (value instanceof String s)
			return as == String.class ? s : getMapper(as).mapString(s);
		if (value instanceof Boolean b)
			return as == boolean.class || as == Boolean.class ? b : getMapper(as).mapBoolean(b);
		if (value instanceof Number n)
			return yieldNumber(as, n);
		throw new UnsupportedOperationException("JSON value not supported: "+ value);
	}

	private Object yieldNumber(Class<?> as, Number n) {
		if (as == Number.class) return n;
		if (as == int.class || as == Integer.class) return n.intValue();
		if (as == long.class || as == Long.class) return n.longValue();
		if (as == float.class || as == Float.class) return n.floatValue();
		if (as == double.class || as == Double.class) return n.doubleValue();
		if (as.isInstance(n)) return n;
		return getMapper(as).mapNumber(n);
	}

	private Object yieldContinuation(Member member, Object[] args) {
		int cp = in.readCharSkipWhitespace();
		if (member.isConsumer()) {
			switch (cp) {
				case '[' -> arrayViaConsumer(member, args);
				case '{' -> objectViaConsumer(member, args);
				default -> throw formatException("an array or object", cp);
			}
			return null;
		}
		return switch (cp) {
			case '[' -> arrayAsStream(member);
			case '{' -> objectAsStream(member);
			default -> throw formatException("an array or object", cp);
		};
	}

	private void readMembersToContinuation(JsonFrame frame) {
		if (frame.encounteredContinuation != null || frame.isClosed)
			return;
		int cp = ',';
		if (!frame.isOpened) {
			in.readCharSkipWhitespaceAndExpect('{');
			frame.isOpened = true;
		} else if (frame.isContinued) {
			cp = in.readCharSkipWhitespace();
			frame.isContinued = false;
		}
		Map<String, Member> frameMembers = MEMBERS_BY_TYPE.get(frame.type);
		while (cp != '}') {
			if (cp != ',')
				throw formatException("comma or end of object", cp);
			in.readCharSkipWhitespaceAndExpect('"');
			String name = in.readString();
			in.readCharSkipWhitespaceAndExpect(':');
			Member member = frameMembers.get(name);
			if (member == null) {
				//TODO skip value
			} else if (member.isContinuation()) {
				frame.encounteredContinuation = name;
				frame.isContinued = true;
				return;
			}
			frame.encounteredContinuation = null;
			cp = in.readAutodetect(val -> frame.setDirectValue(name, val));
		}
		frame.isClosed = true;
	}

	private void objectViaConsumer(Member member, Object[] args) {
		JsonFrame frame = pushFrame(member);
		@SuppressWarnings("unchecked")
		Consumer<Object> consumer = (Consumer<Object>) args[0];
		int cp = ',';
		while (cp != '}') {
			if (cp != ',') throw formatException("comma or end of object", cp);
			in.readCharSkipWhitespaceAndExpect('{');
			in.readCharSkipWhitespaceAndExpect('"');
			String key = in.readString();
			in.readCharSkipWhitespaceAndExpect(':');
			frame.setDirectValue(OBJECT_KEY, key);
			frame.nextInStream();
			frame.itemNo++;
			consumer.accept(newProxy(member.streamType(), this));
			cp = in.readCharSkipWhitespace();
		}
		popFrame(member);
	}

	private void arrayViaConsumer(Member member, Object[] args) {
		JsonFrame frame = pushFrame(member);
		@SuppressWarnings("unchecked")
		Consumer<Object> consumer = (Consumer<Object>) args[0];
		int cp = ',';
		while (cp != ']') {
			if (cp != ',') throw formatException("comma or end of array", cp);
			in.readCharSkipWhitespaceAndExpect('{');
			frame.nextInStream();
			frame.isOpened = true;
			frame.itemNo++;
			consumer.accept(newProxy(member.streamType(), this));
			cp = in.readCharSkipWhitespace();
		}
		popFrame(member);
	}

	private Object objectAsStream(Member member) {
		JsonFrame frame = pushFrame(member);
		Iterator<?> iter = new Iterator<>() {
			@Override
			public boolean hasNext() {
				int cp = in.readCharSkipWhitespace();
				if (cp == '}') {
					frame.itemNo = -(frame.itemNo + 1);
					popFrame(member);
					return false;
				}
				if (frame.itemNo > 0) {
					if (cp != ',') throw formatException("comma or end of object", cp);
					cp = in.readCharSkipWhitespace();
				}
				if (cp != '"') throw formatException("double quotes of member name", cp);
				// at the end we always have read the opening double quote of the member name already
				return true;
			}

			@Override
			public Object next() {
				if (frame.itemNo < 0) throw new NoSuchElementException("" + (-frame.itemNo + 1));
				frame.itemNo++;
				String key = in.readString(); // includes closing "
				in.readCharSkipWhitespaceAndExpect(':');
				frame.nextInStream();
				frame.setDirectValue(OBJECT_KEY, key);
				return newProxy(member.streamType(), JsonStream.this);
			}
		};
		return member.isStream() ? toStream(iter) : iter;
	}

	private Object arrayAsStream(Member member) {
		JsonFrame frame = pushFrame(member);
		Iterator<?> iter = new Iterator<>() {
			@Override
			public boolean hasNext() {
				int cp = in.readCharSkipWhitespace(); // reads either { (item) or ] (end of continuation)
				if (cp == ']') {
					frame.itemNo = -(frame.itemNo + 1);
					popFrame(member);
					return false;
				}
				if (frame.itemNo > 0) {
					if (cp != ',') throw formatException("comma or end of array", cp);
					cp = in.readCharSkipWhitespace();
				}
				if (cp != '{') throw formatException("start of object or end of array", cp);
				return true;
			}

			@Override
			public Object next() {
				if (frame.itemNo < 0) throw new NoSuchElementException("" + (-frame.itemNo + 1));
				frame.nextInStream();
				frame.isOpened = true; // { already read by hasNext
				frame.itemNo++;
				return newProxy(member.streamType(), JsonStream.this);
			}
		};
		return member.isStream() ? toStream(iter) : iter;
	}

	private JsonFrame currentFrame() {
		return stack.getFirst();
	}

	private void popFrame(Member member) {
		stack.removeFirst();
		currentFrame().markAsConsumed(member.name());
	}

	private JsonFrame pushFrame(Member member) {
		return pushFrame(member.streamType());
	}

	private JsonFrame pushFrame(Class<?> type) {
		initStreamType(type);
		JsonFrame frame = new JsonFrame(type);
		stack.addFirst(frame);
		return frame;
	}

	private static Stream<?> toStream(Iterator<?> iter) {
		return StreamSupport.stream(spliteratorUnknownSize(iter, Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE), false);
	}

	@SuppressWarnings("unchecked")
	private static <A> A newProxy(Class<A> type, JsonStream handler) {
		return (A) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] {type}, handler);
	}

	/**
	 * Holds the information and parse state for JSON input for a single JSON object level.
	 */
	private static class JsonFrame {
		final Class<?> type;
		/**
		 * The values of members read so far, for continuation a null value is put once the continuation was used once.
		 */
		private final Map<String, Serializable> values = new HashMap<>();
		boolean isOpened;
		boolean isClosed;
		boolean isContinued;
		int itemNo;
		/**
		 * the continuation input that is not yet been processed - if set we are at the start of that member value in the input stream
		 */
		String encounteredContinuation;

		private JsonFrame(Class<?> type) {
			this.type = type;
		}

		void setDirectValue(String member, Serializable value) {
			values.put(member, value);
		}

		boolean hasValue(String member) {
			return values.containsKey(member);
		}

		Serializable directValue(String member) {
			return values.get(member);
		}

		void markAsConsumed(String member) {
			setDirectValue(member, null);
		}

		void nextInStream() {
			isOpened = false;
			isClosed = false;
			isContinued = false;
			encounteredContinuation = null;
			values.clear();
		}
	}

	enum MemberProcessing {DIRECT, STREAM, ITERATOR, CONSUMER}

	/**
	 * Holds the "meta" information on a member {@link Method} of a {@link Stream} interface type.
	 */
	private static record Member(
			MemberProcessing processing,
			String name,
			Class<?> type, // root type
			Class<?> streamType, // of the items used in a stream for this member - only set if this #isContinuation
			boolean hasDefault,
			Object nullValue) {

		public Member(Method m) {
			this(processing(m), name(m), m.getReturnType(), streamType(m), hasDefault(m), nullValue(m));
		}

		boolean isContinuation() {
			return processing != MemberProcessing.DIRECT;
		}

		boolean isStream() {
			return processing == MemberProcessing.STREAM;
		}

		boolean isConsumer() {
			return processing == MemberProcessing.CONSUMER;
		}

		private static MemberProcessing processing(Method m) {
			Class<?> as = m.getReturnType();
			if (as == Stream.class) return MemberProcessing.STREAM;
			if (as == Iterator.class) return MemberProcessing.ITERATOR;
			if (as == void.class
					&& m.getParameterCount() == 1
					&& m.getParameterTypes()[0] == Consumer.class
					&& isInterface(parameterItemType(m))) return MemberProcessing.CONSUMER;
			return MemberProcessing.DIRECT;
		}

		private static String name(Method m) {
			JsonMember member = m.getAnnotation(JsonMember.class);
			String name = m.getName();
			if (name.startsWith("get") && name.length() > 3 && isUpperCase(name.charAt(3))) {
				name = toLowerCase(name.charAt(3)) + name.substring(4);
			} else if (name.startsWith("is") && name.length() > 2 && isUpperCase(name.charAt(2)))
				name = toLowerCase(name.charAt(2)) + name.substring(3);
			if (member == null) return name;
			if (!member.name().isEmpty()) return member.name();
			if (member.key()) return OBJECT_KEY;
			return name;
		}

		private static Class<?> streamType(Method m) {
			return switch (processing(m)) {
				case DIRECT -> null;
				case CONSUMER -> parameterItemType(m);
				case STREAM, ITERATOR -> (Class<?>) ((ParameterizedType) m.getGenericReturnType()).getActualTypeArguments()[0];
			};
		}

		private static boolean hasDefault(Method m) {
			return m.getParameterCount() == 1 && m.getGenericReturnType().equals(m.getGenericParameterTypes()[0]);
		}

		private static Object nullValue(Method m) {
			Class<?> as = m.getReturnType();
			if (!as.isPrimitive()) {
				return switch (processing(m)) {
					case DIRECT, CONSUMER -> null;
					case STREAM -> Stream.empty();
					case ITERATOR -> emptyIterator();
				};
			}
			if (as == long.class)		return 0L;
			if (as == float.class)	return 0f;
			if (as == double.class)	return 0d;
			if (as == boolean.class)return false;
			if (as == void.class)		return null;
			return 0;
		}

		private static Class<?> parameterItemType(Method m) {
			return toClassType(((ParameterizedType) m.getGenericParameterTypes()[0]).getActualTypeArguments()[0]);
		}

		private static Class<?> toClassType(Type type) {
			if (type instanceof Class<?> c) return c;
			if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
			return null;
		}

		private static boolean isInterface(Class<?> t) {
			return t != null && t.isInterface();
		}
	}
}

