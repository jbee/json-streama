package se.jbee.json.stream;

import static java.util.Collections.unmodifiableMap;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static se.jbee.json.stream.JavaMember.ProcessingType.*;
import static se.jbee.json.stream.JavaMember.isProxyInterface;
import static se.jbee.json.stream.JsonFormatException.unexpectedInputCharacter;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import se.jbee.json.stream.JsonToJava.ConstantNull;
import se.jbee.json.stream.JsonToJava.JsonTo;

/**
 * Reading complex JSON documents as streams using user defined interfaces and {@link Stream}/{@link
 * Iterator}s as API which is implemented with dynamic {@link Proxy} instances to make the stream
 * data accessible.
 *
 * <p>(c) 2022
 *
 * <p>The implementation is light on memory allocation and pays with computation. Each access has to
 * do 2-3 small map look-ups and check several type state primitives.
 *
 * <p>The nesting of JSON input document is processed Java stack independent, a stack overflow will
 * not occur.
 *
 * @author Jan Bernitt
 */
public final class JsonStream implements InvocationHandler {

  /*
  Public API
   */

  /**
   * A {@link Factory} can be used to share a {@link JsonToJava} mapping.
   *
   * <p>This is useful both for consistency of mapping and reusing and sharing pre-computed mapping
   * metadata beyond one stream root.
   */
  public interface Factory {

    <T> T ofRoot(Class<T> objType, JsonInputStream in);

    <T> Stream<T> of(Class<T> streamType, JsonInputStream in);

    <T> Iterator<T> iterator(Class<T> streamType, JsonInputStream in);
  }

  public static Factory fromFactory(JsonToJava mapping) {
    return mapping == JsonToJava.DEFAULT || mapping == null
        ? DEFAULT__MAPPING_FACTORY
        : new CachingFactory(mapping, new ConcurrentHashMap<>());
  }

  public static <T> T ofRoot(Class<T> objType, JsonInputStream in) {
    return ofRoot(objType, in, JsonToJava.DEFAULT);
  }

  public static <T> T ofRoot(Class<T> objType, JsonInputStream in, JsonToJava mapping) {
    return ofRoot(objType, in, mapping, new IdentityHashMap<>());
  }

  public static <T> Stream<T> of(Class<T> streamType, JsonInputStream in) {
    return of(streamType, in, JsonToJava.DEFAULT);
  }

  public static <T> Stream<T> of(Class<T> streamType, JsonInputStream in, JsonToJava mapping) {
    return toStream(iterator(streamType, in, mapping, new IdentityHashMap<>()));
  }

  public static <T> Iterator<T> iterator(Class<T> streamType, JsonInputStream in) {
    return iterator(streamType, in, JsonToJava.DEFAULT);
  }

  public static <T> Iterator<T> iterator(
      Class<T> streamType, JsonInputStream in, JsonToJava mapping) {
    return iterator(streamType, in, mapping, new IdentityHashMap<>());
  }

  /*
  Private
   */

  private static final int STREAM_CHARACTERISTICS =
      Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE;

  private static final CachingFactory DEFAULT__MAPPING_FACTORY =
      new CachingFactory(JsonToJava.DEFAULT, new ConcurrentHashMap<>());

  private record CachingFactory(JsonToJava mapping, Map<JavaMember, ProxyInfo> cache)
      implements Factory {
    @Override
    public <T> T ofRoot(Class<T> objType, JsonInputStream in) {
      return JsonStream.ofRoot(objType, in, mapping, cache);
    }

    @Override
    public <T> Stream<T> of(Class<T> streamType, JsonInputStream in) {
      return toStream(iterator(streamType, in));
    }

    @Override
    public <T> Iterator<T> iterator(Class<T> streamType, JsonInputStream in) {
      return JsonStream.iterator(streamType, in, mapping, cache);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T ofRoot(
      Class<T> objType, JsonInputStream in, JsonToJava mapping, Map<JavaMember, ProxyInfo> cache) {
    JsonStream handler = new JsonStream(in, mapping, cache);
    JavaMember root = JavaMember.newRootMember(PROXY_OBJECT, objType, objType);
    JsonFrame top = handler.pushFrame(root);
    return (T) top.proxy;
  }

  private static <T> Iterator<T> iterator(
      Class<T> streamType,
      JsonInputStream in,
      JsonToJava mapping,
      Map<JavaMember, ProxyInfo> cache) {
    JavaMember.ProcessingType type =
        isProxyInterface(streamType) ? PROXY_ITERATOR : MAPPED_ITERATOR;
    JavaMember member = JavaMember.newRootMember(type, Stream.class, streamType);
    JsonStream handler = new JsonStream(in, mapping, cache);
    JsonFrame frame =
        type == PROXY_ITERATOR
            ? null
            : new JsonFrame(
                new ProxyInfo(member, Map.of(member.jsonName(), member), mapping), null);
    @SuppressWarnings("unchecked")
    Iterator<T> res = (Iterator<T>) handler.yieldStreaming(mapping, frame, member, new String[0]);
    return res;
  }

  private final JsonParser in;
  private final JsonToJava mapping;

  /**
   * The currently processed frame is always on top (first), the root object or array of the JSON
   * input is always at the bottom (last).
   */
  private final Deque<JsonFrame> stack = new LinkedList<>();

  private final Map<JavaMember, ProxyInfo> cache;

  private JsonStream(JsonInputStream in, JsonToJava mapping, Map<JavaMember, ProxyInfo> cache) {
    this.in = new JsonParser(in, this::toString);
    this.mapping = mapping;
    this.cache = cache;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Class<?> declaringClass = method.getDeclaringClass();
    if (method.isDefault())
      throw new UnsupportedOperationException("Default methods cannot be used on proxies yet.");
    if (declaringClass == Object.class) {
      return switch (method.getName()) {
        case "toString" -> toString();
        case "hashCode" -> -1;
        case "equals" -> false;
        default -> null;
      };
    }

    JsonFrame frame = currentFrame();

    if (frame.proxy != proxy)
      throw new IllegalStateException("Parent proxy called out of order\nat: " + this);

    JavaMember member = JavaMember.getMember(method);

    // register any consumers
    if (member.processingType().isConsumer()) {
      String name = member.jsonName();
      frame.addCallback(name, (Consumer<?>) args[0]);
      // if we suspended at a consumer which now is available continue with it
      if (name.equals(frame.suspendedAtMember)) {
        yieldConsumer(frame, member, frame.getCallback(name));
        frame.suspendedAtMember = null;
        frame.isSuspended = true; //needs to read , or }
      }
    }

    readSimpleMembersAndSuspend(frame);
    return yieldValue(frame, member, args);
  }

  private Object yieldValue(JsonFrame frame, JavaMember member, Object[] args) {
    if (member.processingType().isSuspending()) {
      if (member.processingType().isConsumer()) { // consumer has no immediate result
        //frame.checkNotAlreadyProcessed(member);
        return null; // = void (consumer either has been fed or will be fed later)
      }

      if (!member.jsonName().equals(frame.suspendedAtMember)) {
        frame.checkNotAlreadyProcessed(member);
        // assume the input does not contain the requested member so its value is empty
        frame.markAsProcessed(member, -1);
        return member.processingType().isStreaming()
            ? yieldStreamUndefined(mapping, member, args)
            : toJavaType(frame.info, member, null, args);
      }
      frame.markAsProcessed(member, 0); // start of streaming...
      // what we have is what we now are going to handle so afterwards we could do next one
      frame.suspendedAtMember = null;
      return yieldStreaming(mapping, frame, member, args);
    }
    if (member.processingType() == RAW_VALUES) return frame.getAnyOtherValue();
    return toJavaType(frame.info, member, frame.getRawValue(member), args);
  }

  private Object yieldStreaming(
      JsonToJava mapping, JsonFrame frame, JavaMember member, Object[] args) {
    int cp = in.readCharSkipWhitespace();
    if (cp == 'n') { // streaming member is declared JSON null
      in.skipCodePoints(3); // ull
      return yieldStreamUndefined(mapping, member, args);
    }
    if (cp != '[' && cp != '{') throw formatException(cp, '[', '{');
    if (cp == '[') {
      // stream from array
      return switch (member.processingType()) {
          // proxy values
        case PROXY_STREAM -> arrayAsProxyStream(member);
        case PROXY_ITERATOR -> arrayAsProxyIterator(member);
        case PROXY_CONSUMER -> arrayViaProxyConsumer(member, args);
          // mapped values
        case MAPPED_CONSUMER -> arrayViaMappedConsumer(frame, member, args);
        case MAPPED_ITERATOR -> arrayAsMappedIterator(frame, member, args);
        case MAPPED_STREAM -> arrayAsMappedStream(frame, member, args);
        default -> throw new UnsupportedOperationException("stream of " + member.processingType());
      };
    }
    // stream from object
    return switch (member.processingType()) {
        // proxy values
      case PROXY_OBJECT -> objectAsProxy(member);
      case PROXY_STREAM -> objectAsProxyStream(member);
      case PROXY_ITERATOR -> objectAsProxyIterator(member);
      case PROXY_CONSUMER -> objectAsProxyConsumer(member, args);
        // mapped values
      case MAPPED_CONSUMER -> objectAsMappedEntryConsumer(frame, member, args);
      case MAPPED_ITERATOR -> objectAsMappedEntryIterator(frame, member, args);
      case MAPPED_STREAM -> objectAsMappedEntryStream(frame, member, args);
      default -> throw new UnsupportedOperationException("stream of " + member.processingType());
    };
  }

  private Object yieldStreamUndefined(JsonToJava mapping, JavaMember member, Object[] args) {
    member.checkConstraintMinOccur(0);
    return member.nulls().hasDefaultParameter()
        ? getDefaultValue(member, args)
        // NB: this is the stream type mapped, "nulls" has value type null value
        : mapping.mapTo(member.types().returnType()).mapNull().get();
  }

  private void yieldConsumer(JsonFrame frame, JavaMember member, Consumer<?> callback) {
    frame.markAsProcessed(member, 0); // start of streaming...
    yieldStreaming(mapping, frame, member, new Object[] {callback} );
  }

  private void readSimpleMembersAndSuspend(JsonFrame frame) {
    if (frame.isClosed || frame.suspendedAtMember != null) return;
    int cp = ',';
    if (!frame.isOpened) {
      in.readCharSkipWhitespace('{');
      frame.isOpened = true;
    } else if (frame.isSuspended) {
      cp = in.readCharSkipWhitespace(); // should be , or }
      frame.isSuspended = false;
    }

    while (cp != '}') {
      if (cp != ',') throw formatException(cp, ',', '}');
      cp = in.readCharSkipWhitespace(); // should be either " of member or }
      if (cp == '}') { // this is the case for JSON: {}
        frame.streamCompleteProxy();
        // NB: no frame was pushed yet so nothing to pop
        return;
      }
      if (cp != '"') throw formatException(cp, '"', '}');
      String name = in.readString();
      in.readCharSkipWhitespace(':');
      JavaMember member = frame.info.getMemberByJsonName(name);
      if (member == null) {
        if (frame.info.anyOtherValueMember != null) {
          cp = in.readNodeDetect(val -> frame.addAnyOtherRawValue(name, val));
        } else {
          // input has a member that is not mapped to java, we ignore it
          cp = in.skipNodeDetect();
        }
        frame.suspendedAtMember = null;
      } else if (member.processingType().isSuspending()) {
        frame.checkNotAlreadyProcessed(member);
        if (member.processingType().isConsumer()) {
          Consumer<?> callback = frame.getCallback(name);
          if (callback != null) {
            yieldConsumer(frame, member, callback);
            cp = in.readCharSkipWhitespace(); // , or } after the streaming member
            continue; // next member
          }
        }
        frame.suspendedAtMember = member.jsonName();
        frame.isSuspended = true;
        return;
      } else {
        frame.suspendedAtMember = null;
        // MAPPED_VALUE
        // TODO are we accepting the type of the value?
        // TODO also: limit size of collections
        cp = in.readNodeDetect(val -> frame.setRawValue(member, val));
      }
    }
    if (!frame.isClosed) {
      frame.isClosed = true;
      // TODO do we need to mark the proxy as processed by setting some raw value for it? should be
      // need to end frame of single proxy objects (unless it is the root)
      if (frame.info.usage.processingType() == PROXY_OBJECT && stack.size() > 1)
        popFrame(frame.info.usage);
    }
  }

  private static Object getDefaultValue(JavaMember member, Object[] args) {
    Object val = args[0];
    return val instanceof Supplier<?> s ? s.get() : val;
  }

  private static Object toJavaType(ProxyInfo info, JavaMember member, Object value, Object[] args) {
    if (value == null)
      return member.nulls().hasDefaultParameter()
          ? getDefaultValue(member, args)
          : info.nullValues[member.index()].get();
    if (value instanceof List<?> list) return toJavaCollection(info, member, list);
    if (value instanceof Map<?, ?> map) return toJavaMap(info, member, map);
    return toSimpleJavaType(info.getJsonToValue(member), value);
  }

  private static Object toSimpleJavaType(JsonTo<?> to, Object value) {
    Class<?> toClass = to.to();
    if (toClass == Serializable.class || toClass.isInstance(value))
      return value; // => keep as is (mixed values)
    if (value instanceof String s) return to.mapString().apply(s);
    if (value instanceof Boolean b)
      return toClass == boolean.class ? value : to.mapBoolean().apply(b);
    if (value instanceof Number n) return to.mapNumber().apply(n);
    throw new UnsupportedOperationException("JSON value not supported: " + value);
  }

  /**
   * A JSON array is mapped to a java collection subtype. The elements are always "simple" mapped
   * values.
   */
  private static Collection<?> toJavaCollection(ProxyInfo info, JavaMember member, List<?> list) {
    Class<?> collectionType = member.types().collectionType();
    JsonTo<?> jsonToValue = info.getJsonToValue(member);
    Stream<Object> stream = list.stream().map(v -> toSimpleJavaType(jsonToValue, v));
    // TODO make this if-type-switch flexible so more collection types can be added by users?
    if (collectionType == List.class || collectionType == Collection.class) return stream.toList();
    if (collectionType == Set.class) return stream.collect(toSet());
    throw new UnsupportedOperationException("Collection type not supported: " + collectionType);
  }

  private static Map<?, ?> toJavaMap(ProxyInfo info, JavaMember member, Map<?, ?> map) {
    JsonTo<?> jsonToKey = info.getJsonToKey(member);
    JsonTo<?> jsonToValue = info.getJsonToValue(member);
    return map.entrySet().stream()
        .collect(
            toMap(
                e -> toSimpleJavaType(jsonToKey, e.getKey()),
                e -> toSimpleJavaType(jsonToValue, e.getValue())));
  }

  private Void objectAsProxyConsumer(JavaMember member, Object[] args) {
    JsonFrame frame = pushFrame(member);
    @SuppressWarnings("unchecked")
    Consumer<Object> consumer = (Consumer<Object>) args[0];
    int cp = ',';
    while (cp != '}') {
      if (cp != ',') throw formatException(cp, ',', '}');
      in.readCharSkipWhitespace('{');
      in.readCharSkipWhitespace('"');
      String key = in.readString();
      in.readCharSkipWhitespace(':');
      frame.streamNextProxy();
      frame.setRawValue(null, key);
      consumer.accept(frame.proxy);
      cp = in.readCharSkipWhitespace();
    }
    frame.streamCompleteProxy();
    popFrame(member);
    return null;
  }

  private Void arrayViaProxyConsumer(JavaMember member, Object[] args) {
    JsonFrame frame = pushFrame(member);
    @SuppressWarnings("unchecked")
    Consumer<Object> consumer = (Consumer<Object>) args[0];
    int cp = ',';
    while (cp != ']') {
      if (cp != ',') throw formatException(cp, ',', ']');
      in.readCharSkipWhitespace('{');
      frame.streamNextProxy();
      frame.isOpened = true;
      consumer.accept(frame.proxy);
      cp = in.readCharSkipWhitespace();
    }
    frame.streamCompleteProxy();
    popFrame(member);
    return null; // = void
  }

  private Object objectAsProxy(JavaMember member) {
    JsonFrame frame = pushFrame(member);
    frame.isOpened = true;
    return frame.proxy;
  }

  private Stream<?> objectAsProxyStream(JavaMember member) {
    return toStream(objectAsProxyIterator(member));
  }

  private Iterator<?> objectAsProxyIterator(JavaMember member) {
    JsonFrame frame = pushFrame(member);
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        int cp = in.readCharSkipWhitespace();
        if (cp == '}') {
          frame.streamCompleteProxy();
          popFrame(member);
          return false;
        }
        frame.streamNextProxy();
        if (frame.proxyStreamItemIndex > 0) {
          if (cp != ',') throw formatException(cp, ',', '}');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '"') throw formatException(cp, '"');
        return true;
      }

      @Override
      public Object next() {
        if (frame.isClosed) throw new NoSuchElementException("" + frame.proxyStreamItemIndex);
        // opening " already consumed in hasNext
        String key = in.readString(); // includes closing "
        in.readCharSkipWhitespace(':');
        frame.setRawValue(null, key);
        return frame.proxy;
      }
    };
  }

  private Stream<?> arrayAsProxyStream(JavaMember member) {
    return toStream(arrayAsProxyIterator(member));
  }

  private Iterator<?> arrayAsProxyIterator(JavaMember member) {
    JsonFrame frame = pushFrame(member);
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        // reads either { (item) or ] (end of streaming member)
        int cp = in.readCharSkipWhitespace();
        if (cp == ']') {
          frame.streamCompleteProxy();
          popFrame(member);
          return false;
        }
        frame.streamNextProxy();
        if (frame.proxyStreamItemIndex > 0) {
          if (cp != ',') throw formatException(cp, ',', ']');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '{') throw formatException(cp, ']', '{');
        return true;
      }

      @Override
      public Object next() {
        if (frame.isClosed) throw new NoSuchElementException("" + frame.proxyStreamItemIndex);
        frame.isOpened = true; // { already read by hasNext
        return frame.proxy;
      }
    };
  }

  private Void arrayViaMappedConsumer(JsonFrame frame, JavaMember member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Object> consumer = (Consumer<Object>) args[0];
    arrayAsMappedIterator(frame, member, args).forEachRemaining(consumer);
    return null; // = void
  }

  private Stream<?> arrayAsMappedStream(JsonFrame frame, JavaMember member, Object[] args) {
    return toStream(arrayAsMappedIterator(frame, member, args));
  }

  private Iterator<?> arrayAsMappedIterator(JsonFrame frame, JavaMember member, Object[] args) {
    frame.mappedStreamItemIndex = -1;
    return new Iterator<>() {
      final JsonTo<?> key2Java = frame.info.getJsonToKey(member);
      int cp = in.peek() == ']' ? ']' : ',';
      // TODO allow { } with key/value as elements
      @Override
      public boolean hasNext() {
        if (cp == ']') {
          frame.markAsProcessed(member, frame.mappedStreamItemIndex + 1);
          frame.streamCompleteMappedValue();
          return false;
        }
        frame.streamNextMappedValue();
        if (frame.mappedStreamItemIndex > 0 && cp != ',') throw formatException(cp, ',', ']');
        return true;
      }

      @Override
      public Object next() {
        if (cp == ']') throw new NoSuchElementException("" + frame.mappedStreamItemIndex);
        // reading value and either , or ]
        cp = in.readNodeDetect(val -> frame.setRawValue(member, val));
        Object value = toJavaType(frame.info, member, frame.getRawValue(member), args);
        return key2Java == null
            ? value
            : new SimpleEntry<>(toSimpleJavaType(key2Java, frame.mappedStreamItemIndex), value);
      }
    };
  }

  private Void objectAsMappedEntryConsumer(JsonFrame frame, JavaMember member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Entry<?, ?>> consumer = (Consumer<Entry<?, ?>>) args[0];
    objectAsMappedEntryIterator(frame, member, args).forEachRemaining(consumer);
    return null; // = void
  }

  private Stream<Entry<?, ?>> objectAsMappedEntryStream(
      JsonFrame frame, JavaMember member, Object[] args) {
    return toStream(objectAsMappedEntryIterator(frame, member, args));
  }

  private Iterator<Entry<?, ?>> objectAsMappedEntryIterator(
      JsonFrame frame, JavaMember member, Object[] args) {
    return new Iterator<>() {
      final JsonTo<?> key2Java = frame.info.getJsonToKey(member);
      int cp = in.readCharSkipWhitespace();

      @Override
      public boolean hasNext() {
        if (cp == '}') {
          frame.markAsProcessed(member, frame.mappedStreamItemIndex);
          return false;
        }
        frame.streamNextMappedValue();
        if (frame.mappedStreamItemIndex > 0) {
          if (cp != ',') throw formatException(cp, ',', '}');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '"') throw formatException(cp, '"');
        return true;
      }

      @Override
      public Entry<?, ?> next() {
        if (cp == '}') throw new NoSuchElementException("" + frame.mappedStreamItemIndex);
        // opening " already consumed in hasNext
        String key = in.readString();
        in.readCharSkipWhitespace(':');
        // reading value and either , or }
        cp = in.readNodeDetect(val -> frame.setRawValue(member, val));
        Object value = toJavaType(frame.info, member, frame.getRawValue(member), args);
        return new SimpleEntry<>(toSimpleJavaType(key2Java, key), value);
      }
    };
  }

  private static <T> Stream<T> toStream(Iterator<T> iter) {
    return StreamSupport.stream(spliteratorUnknownSize(iter, STREAM_CHARACTERISTICS), false);
  }

  private JsonFrame currentFrame() {
    return stack.peekFirst();
  }

  private void popFrame(JavaMember member) {
    JsonFrame done = stack.removeFirst();
    JsonFrame parent = currentFrame();
    if (parent != null) parent.markAsProcessed(member, done.proxyStreamItemIndex + 1);
  }

  private JsonFrame pushFrame(JavaMember member) {
    Class<?> type = member.types().valueType();
    ProxyInfo info =
        cache.computeIfAbsent(
            member, m -> new ProxyInfo(m, JavaMember.getMembersOf(type), mapping));
    JsonFrame top = new JsonFrame(info, newProxy(type, this));
    stack.addFirst(top);
    return top;
  }

  private static Object newProxy(Class<?> type, JsonStream handler) {
    return Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, handler);
  }

  /**
   * The "static" properties of a {@link JsonFrame}.
   *
   * <p>This is combination of a {@link JavaMember} and the {@link JsonToJava} mapping which might
   * be different within every stream.
   */
  private static final class ProxyInfo {

    private final JsonToJava mapping;
    /** The java member (or type) the frames represent */
    private final JavaMember usage;
    /** Values for JSON null or undefined for each member by {@link JavaMember#index()} */
    private final Supplier<?>[] nullValues;
    /** Converter to use for each member value by {@link JavaMember#index()} */
    private final JsonTo<?>[] jsonToValueTypes;
    /** Converter to use for each member key (in map values) by by {@link JavaMember#index()} */
    private final JsonTo<?>[] jsonToKeyTypes;
    /** */
    private final Map<String, JavaMember> membersByJsonName;

    private final JavaMember anyOtherValueMember;

    ProxyInfo(JavaMember usage, Map<String, JavaMember> membersByJsonName, JsonToJava mapping) {
      this.mapping = mapping;
      this.usage = usage;
      int size = membersByJsonName.size() + 1;
      this.membersByJsonName = membersByJsonName;
      this.nullValues = new Supplier[size];
      this.jsonToValueTypes = new JsonTo<?>[size];
      this.jsonToKeyTypes = new JsonTo<?>[size];
      this.anyOtherValueMember =
          membersByJsonName.values().stream()
              .filter(m -> m.processingType() == RAW_VALUES)
              .findFirst()
              .orElse(null);
      membersByJsonName.values().forEach(this::init);
    }

    private void init(JavaMember m) {
      int i = m.index();
      jsonToValueTypes[i] = m.jsonToValueType(mapping);
      jsonToKeyTypes[i] = m.jsonToKeyType(mapping);
      JsonTo<?> nullToJava = mapping.mapTo(m.types().nullValueType());
      JavaMember.Nulls nulls = m.nulls();
      nullValues[i] =
          nulls.defaultValue() == null
              ? nulls.retainNulls() ? new ConstantNull<>(null) : nullToJava.mapNull()
              : new ConstantNull<>(toJavaType(this, m, nulls.defaultValue(), new Object[0]));
    }

    JavaMember getMemberByJsonName(String jsonName) {
      return membersByJsonName.get(jsonName);
    }

    JsonTo<?> getJsonToValue(JavaMember member) {
      return jsonToValueTypes[member.index()];
    }

    JsonTo<?> getJsonToKey(JavaMember member) {
      return jsonToKeyTypes[member.index()];
    }
  }

  /**
   * Holds the information and parse state for JSON input for a single JSON object or array level.
   */
  private static final class JsonFrame implements Iterable<Entry<String, Object>> {

    /**
     * Metadata on the proxy. Most of all this is about the members defined by the methods of the
     * proxy interface.
     */
    final ProxyInfo info;

    /**
     * The proxy instance used (gets reused during proxy item streaming for each and all stream
     * items)
     */
    final Object proxy;

    /**
     * The values of members read so far, for streaming members this holds the number of streamed
     * items once the member is done.
     *
     * <p>Index 0 is always for the key member, if no such member exist first value is at index 1
     *
     * <p>Raw values refers to this being of the Java equivalent type of the JSON input. They are
     * not yet converted to the Java target type defined by the member return value.
     */
    private final Object[] rawValues;

    /**
     * For each value (same index as {@link #rawValues}) this remembers the order the value appeared
     * in the input as the {@link #memberInputNext} values. This is a relative index or count to the
     * {@link #memberInputBase}. This means any value less than the {@link #memberInputBase} belongs
     * to a previous frame and was not (yet) present in the current frame.
     */
    private final int[] memberInputOrder;

    /** At what {@link #memberInputNext} count the current proxy started */
    private int memberInputBase = 1;
    /** Members read count or the index relative to the {@link #memberInputBase} count */
    private int memberInputNext = 1;

    /** We did read the opening curly bracket of the object */
    boolean isOpened;
    /** We did read the closing curly bracket of the object */
    boolean isClosed;
    /**
     * When finding a member in the frame object that itself is a stream the member is not read
     * until the stream is processed in the code. Until then the parsing pauses at this position.
     * The member itself is processed in a new frame. When that frame is done and control comes back
     * to this frame we should be at the end of the member finding either a comma or the end of the
     * object. This flag is required to remember that we need to read next non whitespace input
     * character to complete the member.
     */
    boolean isSuspended;

    /**
     * A {@link JavaMember.ProcessingType#isStreaming()} input that is not yet been processed - if
     * set we are at the start of that member value in the input stream
     */
    String suspendedAtMember;

    int proxyStreamItemIndex = -1;

    int mappedStreamItemIndex = -1;

    /**
     * This map remembers {@link Consumer}s provided by a member method call in case their
     * input is not yet available so that they can be used once the member occurs in the input.
     */
    private Map<String, Consumer<?>> callbacks;

    private JsonFrame(ProxyInfo info, Object proxy) {
      this.info = info;
      this.proxy = proxy;
      int size = info.membersByJsonName.size() + 1;
      this.rawValues = new Object[size];
      this.memberInputOrder = new int[size];
    }

    void addCallback(String name, Consumer<?> callback) {
      if (callbacks == null)
        callbacks = new HashMap<>(4);
      callbacks.put(name, callback);
    }

    Consumer<?> getCallback(String name) {
      return callbacks == null ? null : callbacks.get(name);
    }

    void setRawValue(JavaMember member, Object rawValue) {
      int index = member == null ? 0 : member.index();
      rawValues[index] = rawValue;
      memberInputOrder[index] = memberInputNext++;
    }

    void addAnyOtherRawValue(String name, Object rawValue) {
      int index = info.anyOtherValueMember.index();
      @SuppressWarnings("unchecked")
      Map<String, Object> anyOtherValues = (Map<String, Object>) rawValues[index];
      if (anyOtherValues == null) {
        anyOtherValues = new HashMap<>();
        rawValues[index] = anyOtherValues;
      }
      anyOtherValues.put(name, rawValue);
    }

    Object getRawValue(JavaMember member) {
      int index = member.index();
      return isRawValueAvailable(index) ? rawValues[index] : null;
    }

    private boolean isRawValueAvailable(int index) {
      return memberInputOrder[index] >= memberInputBase;
    }

    Map<String, ?> getAnyOtherValue() {
      @SuppressWarnings("unchecked")
      Map<String, ?> anyOtherValues =
          info.anyOtherValueMember == null
              ? null
              : (Map<String, ?>) rawValues[info.anyOtherValueMember.index()];
      return anyOtherValues == null ? Map.of() : unmodifiableMap(anyOtherValues);
    }

    void markAsProcessed(JavaMember member, int totalNumberOfItems) {
      setRawValue(member, totalNumberOfItems);
    }

    void streamNextProxy() {
      proxyStreamItemIndex++;
      checkConstraintMaxOccur(proxyStreamItemIndex + 1);
      memberInputBase = memberInputNext;
      mappedStreamItemIndex = -1;
      isOpened = false;
      isClosed = false;
      isSuspended = false;
      suspendedAtMember = null;
      if (info.anyOtherValueMember != null && rawValues[info.anyOtherValueMember.index()] != null)
        ((Map<?, ?>) rawValues[info.anyOtherValueMember.index()]).clear();
    }

    void streamNextMappedValue() {
      mappedStreamItemIndex++;
      checkConstraintMaxOccur(mappedStreamItemIndex + 1);
    }

    void streamCompleteProxy() {
      isClosed = true; // proxy frame level done
      checkConstraintMinOccur(proxyStreamItemIndex + 1);
    }

    void streamCompleteMappedValue() {
      // closed is not true as there is no extra frame level without proxies
      checkConstraintMinOccur(mappedStreamItemIndex + 1);
    }

    private void checkConstraintMaxOccur(int occur) {
      info.usage.checkConstraintMaxOccur(occur);
    }

    private void checkConstraintMinOccur(int occur) {
      info.usage.checkConstraintMinOccur(occur);
    }

    void checkNotAlreadyProcessed(JavaMember member) {
      if (rawValues[member.index()] != null)
        throw JsonSchemaException.outOfOrder(
            member.jsonName(),
            info.membersByJsonName.values().stream()
                .filter(m -> m.processingType().isStreaming())
                .filter(m -> m != member)
                .filter(m -> rawValues[m.index()] != null)
                .map(JavaMember::jsonName));
    }

    /**
     * @return the current values of this frame by member jsonName (only used in {@link #toString()}
     */
    @Override
    public Iterator<Entry<String, Object>> iterator() {
      @SuppressWarnings("unchecked")
      Entry<String, Object>[] entries = new Entry[memberInputOrder.length];
      for (JavaMember m : info.membersByJsonName.values()) {
        int i = m.index();
        if (isRawValueAvailable(i))
          entries[getInputOrder(i)] = new SimpleEntry<>(m.jsonName(), rawValues[i]);
      }
      return Arrays.stream(entries).filter(Objects::nonNull).iterator();
    }

    /**
     * @param index of the member
     * @return 0 - n, starting with 0 for the first member present in the input for the frame/proxy
     */
    private int getInputOrder(int index) {
      return memberInputOrder[index] - memberInputBase;
    }
  }

  @Override
  public String toString() {
    StringBuilder str = new StringBuilder();
    List<JsonFrame> frames = new ArrayList<>();
    stack.descendingIterator().forEachRemaining(frames::add);
    String indent = "";
    boolean isRootArray = !frames.isEmpty() && frames.get(0).proxyStreamItemIndex >= 0;
    if (isRootArray) {
      str.append("[... <").append(frames.get(0).proxyStreamItemIndex).append(">\n");
      indent += '\t';
    }
    for (int i = 0; i < frames.size(); i++) {
      JsonFrame f = frames.get(i);
      if (!f.isOpened) continue;
      str.append(indent).append("{\n");
      for (Entry<String, ?> value : f) {
        Object v = value.getValue();
        String name = value.getKey();
        boolean notLastFrame = i < frames.size() - 1;
        String no = notLastFrame ? "" + (frames.get(i + 1).proxyStreamItemIndex) : "?";
        JavaMember member = f.info.getMemberByJsonName(name);
        if (member == null) continue;
        if (!member.processingType().isStreaming()) {
          str.append(indent).append("\t\"").append(name).append("\": ").append(v).append(",\n");
        } else {
          str.append(indent).append("\t").append('"').append(name);
          // is this a member that is currently streamed by next frame?
          if (notLastFrame && name.equals(frames.get(i + 1).info.usage.jsonName())) {
            str.append("\": [... <").append(no).append(">\n");
          } else
            str.append("\": [")
                .append(Integer.valueOf(-1).equals(v) ? "..." : "<" + v + ">")
                .append("],\n");
        }
      }
      if (f.suspendedAtMember != null) str.append("<").append(f.suspendedAtMember).append("?>");
      if (f.isClosed) str.append(indent).append("}\n");
      indent += '\t';
    }
    if (!frames.isEmpty() && !frames.get(0).isClosed) {
      int unclosedFrames = (int) frames.stream().filter(f -> !f.isClosed).count();
      str.append("\t".repeat(Math.max(0, unclosedFrames - 1))).append("<stream position>");
    }
    return str.toString();
  }

  private JsonFormatException formatException(int found, char... expected) {
    return unexpectedInputCharacter(found, toString(), expected);
  }
}
