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
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
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
		return from(rootObjType, in, JsonMapping.create());
	}

	public static <T> T from(Class<T> rootObjType, IntSupplier in, JsonMapping mapping) {
		// if we deal with a root object having members that "continue" the stream
		JsonStream handler = new JsonStream(in, mapping);
		handler.pushFrame(rootObjType);
		return newProxy(rootObjType, handler);
	}

	public static <T> Stream<T> of(Class<T> streamType, IntSupplier in) {
		return of(streamType, in, JsonMapping.create());
	}
	
	public static <T> Stream<T> of(Class<T> streamType, IntSupplier in, JsonMapping mapping) {
		// if we deal with a root array or "map" object treated as such
		Member member = new Member(true, "", Stream.class, streamType, false, Stream.empty());
		JsonStream handler = new JsonStream(in, mapping);
		handler.pushFrame(streamType);
		@SuppressWarnings("unchecked")
		Stream<T> res = (Stream<T>) handler.yieldContinuation(member);
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
	private final Map<Class<?>, JsonMapper<?>> mappersByReturnType = new HashMap<>();
	/**
	 * The currently processed frame is always at index 0 - this means the top most frame of the JSON structure is at the end.
	 */
	private final Deque<JsonFrame> stack = new LinkedList<>();

	private JsonStream(IntSupplier in, JsonMapping mapping) {
		this.in = new JsonParser(in);
		this.mapping = mapping;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) {
		JsonFrame frame = currentFrame();
		//TODO we could check the proxy type or the method declaring class to see if the call is for the current frame
		readMembersToContinuation(frame);
		Member member = MEMBERS_BY_METHOD.get(method);
		String name = member.name();
		if (member.isContinuation()) {
			if (!name.equals(frame.encounteredContinuation)) {
				if (frame.hasMember(name))
					throw new IllegalStateException("A continuation member can only be used once");
				// assume the input does not contain the requested member so its value is empty
				frame.markAsConsumed(name);
				return member.isStream() ? Stream.empty() : emptyIterator();
			}
			frame.markAsConsumed(name);
			// what we have is what we now are going to handle so afterwards we could do next one
			frame.encounteredContinuation = null;
			return yieldContinuation(member);
		}
		return yieldPrimitive(member, frame.memberValue(name), args);
	}

	private Object yieldPrimitive(Member member, Object value, Object[] args) {
		if (value == null) {
			return member.hasDefault() ? args[0] : member.nullValue();
		}
		Class<?> rt = member.returnType();
		JsonMapper<?> mapper = mappersByReturnType.get(rt);
		if (value instanceof String s)
			return rt == String.class ? s : mapper.mapString(s);
		if (value instanceof Boolean b)
			return rt == boolean.class ? b : mapper.mapBoolean(b);
		if (value instanceof Number n) {
			if (rt == Number.class) return n;
			if (rt == int.class || rt == Integer.class) return n.intValue();
			if (rt == long.class || rt == Long.class) return n.longValue();
			if (rt == float.class || rt == Float.class) return n.floatValue();
			if (rt == double.class || rt == Double.class) return n.doubleValue();
			if (rt.isInstance(n)) return n;
			return mapper.mapNumber(n);
		}
		throw new UnsupportedOperationException("JSON value not supported: "+ value);
	}

	private Object yieldContinuation(Member member) {
		int cp = in.readCharSkipWhitespace();
		return switch (cp) {
			case '[' -> arrayAsStream(member);
			case '{' -> objectAsStream(member);
			default -> throw formatException("n array or object", cp);
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
			cp = in.readAutodetect(val -> frame.setMemberValue(name, val));
		}
		frame.isClosed = true;
	}

	private Object objectAsStream(Member member) {
		JsonFrame frame = pushFrame(member);
		Iterator<?> iter = new Iterator<Object>() {
			@Override
			public boolean hasNext() {
				int cp = in.readCharSkipWhitespace();
				if (cp == '}') {
					frame.n = -(frame.n + 1);
					popFrame(member);
					return false;
				}
				if (frame.n > 0) {
					if (cp != ',')
						throw formatException("comma or end of object", cp);
					cp = in.readCharSkipWhitespace();
				}
				if (cp != '"')
						throw formatException("double quotes of member name", cp);
				// at the end we always have read the opening double quote of the member name already
				return true;
			}

			@Override
			public Object next() {
				if (frame.n < 0)
					throw new NoSuchElementException(""+(-frame.n+1));
				frame.n++;
				String key = in.readString(); // includes closing "
				in.readCharSkipWhitespaceAndExpect(':');
				frame.reset();
				frame.setMemberValue(OBJECT_KEY, key);
				return newProxy(member.streamType(), JsonStream.this );
			}
		};
		return member.isStream() ? toStream(iter) : iter;
	}

	private Object arrayAsStream(Member member) {
		JsonFrame frame = pushFrame(member);
		Iterator<?> iter = new Iterator<Object>() {
			@Override
			public boolean hasNext() {
				int cp = in.readCharSkipWhitespace(); // reads either { (item) or ] (end of continuation)
				if (cp == ']') {
					frame.n = -(frame.n + 1);
					popFrame(member);
					return false;
				}
				if (frame.n > 0) {
					if (cp != ',')
						throw formatException("comma or end of array", cp);
					cp = in.readCharSkipWhitespace();
				}
				if (cp != '{')
					throw formatException("start of object or end of array", cp);
				return true;
			}

			@Override
			public Object next() {
				if (frame.n < 0)
					throw new NoSuchElementException(""+(-frame.n+1));
				frame.reset();
				frame.isOpened = true; // { already read by hasNext
				frame.n++;
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
		public final Class<?> type;
		/**
		 * The values of members read so far, for continuation a null value is put once the continuation was used once.
		 */
		private final Map<String, Serializable> values = new HashMap<>();
		boolean isOpened;
		boolean isClosed;
		boolean isContinued;
		int n;
		/**
		 * the continuation input that is not yet been processed - if set we are at the start of that member value in the input stream
		 */
		String encounteredContinuation;

		private JsonFrame(Class<?> type) {
			this.type = type;
		}

		public void setMemberValue(String name, Serializable value) {
			values.put(name, value);
		}

		public boolean hasMember(String name) {
			return values.containsKey(name);
		}

		Serializable memberValue(String name) {
			return values.get(name);
		}

		void markAsConsumed(String name) {
			setMemberValue(name, null);
		}

		void reset() {
			isOpened = false;
			isClosed = false;
			isContinued = false;
			encounteredContinuation = null;
			values.clear();
		}
	}

	/**
	 * Holds the "meta" information on a member {@link Method} of a {@link Stream} interface type.
	 */
	private static record Member(
			boolean isContinuation,
			String name,
			Class<?> returnType,
			/*
			  The type of the items used in a stream for this member - only set if this #isContinuation
			 */
			Class<?> streamType,
			boolean hasDefault,
			Object nullValue) {

		public Member(Method m) {
			this(isContinuation(m), name(m), m.getReturnType(), streamType(m), hasDefault(m), nullValue(m));
		}

		boolean isStream() {
			return returnType == Stream.class;
		}

		private static boolean isContinuation(Method m) {
			return (m.getReturnType() == Stream.class || m.getReturnType() == Iterator.class);
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
			if (!isContinuation(m))
				return null;
			return (Class<?>)((ParameterizedType)m.getGenericReturnType()).getActualTypeArguments()[0];
		}

		private static boolean hasDefault(Method m) {
			return m.getParameterCount() == 1 && m.getGenericReturnType().equals(m.getGenericParameterTypes()[0]);
		}

		private static Object nullValue(Method m) {
			Class<?> rt = m.getReturnType();
			if (!rt.isPrimitive()) {
				if (!isContinuation(m)) return null;
				if (m.getReturnType() == Stream.class) return Stream.empty();
				if (m.getReturnType() == Iterator.class) return emptyIterator();
				return null;
			}
			if (rt == long.class)		return 0L;
			if (rt == float.class)	return 0f;
			if (rt == double.class)	return 0d;
			if (rt == boolean.class)return false;
			if (rt == void.class)		return null;
			return 0;
		}
	}
}

