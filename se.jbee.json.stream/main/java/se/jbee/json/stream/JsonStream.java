package se.jbee.json.stream;

import java.io.InputStream;
import java.io.Serializable;
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
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Collections.emptyIterator;
import static java.util.Spliterators.spliteratorUnknownSize;
import static se.jbee.json.stream.JsonReader.formatException;

public final class JsonStream implements InvocationHandler {

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

	public static <T> T of(Class<T> rootObjType, InputStream in) {
		// if we deal with a root object having members that "continue" the stream
		JsonStream handler = new JsonStream(in, JsonMapping.create());
		handler.pushFrame(rootObjType);
		return newProxy(rootObjType, handler);
	}

	public static <T> Stream<T> ofItems(Class<T> itemType, InputStream in) {
		// if we deal with a root array or "map" object treated as such
		return null;
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
						membersByName.put(member.name(), member);
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

	private final JsonReader in;
	private final JsonMapping mapping;
	private final Map<Method, JsonMapper<?>> mappers = new HashMap<>();
	/**
	 * The currently processed frame is always at index 0 - this means the top most frame of the JSON structure is at the end.
	 */
	private final Deque<JsonFrame> stack = new LinkedList<>();

	private JsonStream( InputStream in, JsonMapping mapping) {
		this.in = new JsonReader(in);
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
			if (!name.equals(frame.currentContinuation)) {
				if (frame.hasMember(name))
					throw new IllegalStateException("A continuation member can only be used once");
				// assume the input does not contain the requested member so its value is empty
				frame.markAsProcessed(name);
				return member.isStream() ? Stream.empty() : emptyIterator();
			}
			frame.markAsProcessed(name);
			frame.currentContinuation = null; // allow going to next continuation
			int cp = in.readCharSkipWhitespace();
			return switch (cp) {
				case '[' -> arrayAsContinuation(member);
				case '{' -> objectAsContinuation(member);
				default -> throw formatException("n array or object", cp);
			};
		}
		Object value = frame.memberValue(name);
		if (value == null) {
			return member.hasDefault() ? args[0] : member.nullValue();
		}
		JsonMapper<?> mapper = mappers.get(method);
		if (value instanceof String s)
			return member.returnType() == String.class ? s : mapper.mapString(s);
		if (value instanceof Number n)
			return member.returnType() == int.class ? n.intValue() : mapper.mapNumber(n);
		if (value instanceof Boolean b)
			return member.returnType() == boolean.class ? b : mapper.mapBoolean(b);
		throw new UnsupportedOperationException("JSON value not supported: "+value);
	}

	private Object objectAsContinuation(Member member) {
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
				String key = in.readQuotedString(); // includes closing "
				in.readCharSkipWhitespaceAndExpect(':');
				frame.reset();
				frame.setMemberValue(OBJECT_KEY, key);
				return newProxy(member.streamType(), JsonStream.this );
			}
		};
		return member.isStream() ? toStream(iter) : iter;
	}

	private Object arrayAsContinuation(Member member) {
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
		currentFrame().markAsProcessed(member.name());
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

	private void readMembersToContinuation(JsonFrame frame) {
		if (frame.isClosed || frame.currentContinuation != null)
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
			String name = in.readQuotedString();
			in.readCharSkipWhitespaceAndExpect(':');
			Member member = frameMembers.get(name);
			if (member == null) {
				//TODO skip value
			} else if (member.isContinuation()) {
				frame.currentContinuation = name;
				frame.isContinued = true;
				return;
			}
			frame.currentContinuation = null;
			cp = in.readAutodetect(val -> frame.setMemberValue(name, val));
			if (cp != ',' && cp != '}') throw formatException("comma or end of object", cp);
		}
		frame.isClosed = true;
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
		String currentContinuation;

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

		void markAsProcessed(String name) {
			setMemberValue(name, null);
		}

		void reset() {
			isOpened = false;
			isClosed = false;
			isContinued = false;
			currentContinuation = null;
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
			return m.getName();
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
			if (!rt.isPrimitive())
				return null;
			if (rt == long.class)
				return 0L;
			if (rt == float.class)
				return 0f;
			if (rt == double.class)
				return 0d;
			return 0;
		}
	}
}

