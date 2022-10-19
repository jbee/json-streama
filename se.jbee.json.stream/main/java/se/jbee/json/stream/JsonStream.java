package se.jbee.json.stream;

import static java.lang.System.arraycopy;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyIterator;
import static java.util.Collections.unmodifiableMap;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static se.jbee.json.stream.JavaMember.ProcessingType.*;
import static se.jbee.json.stream.JsonFormatException.unexpectedInputCharacter;
import static se.jbee.json.stream.JsonSchemaException.maxOccurExceeded;

import java.io.InputStream;
import java.io.Reader;
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
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
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
 * <p>The nesting of JSON input document is processed java stack independent, a stack overflow will
 * not occur.
 *
 * @author Jan Bernitt
 */
public final class JsonStream implements InvocationHandler {

  private static final int STREAM_CHARACTERISTICS =
      Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE;

  public static IntSupplier asIntSupplier(InputStream in) {
    return JsonParser.from(in);
  }

  public static IntSupplier asIntSupplier(Reader in) {
    return JsonParser.from(in);
  }

  private static final Factory DEFAULT__MAPPING_FACTORY =
      new Factory(JsonToJava.DEFAULT, new ConcurrentHashMap<>());

  private record Factory(JsonToJava toJava, Map<JavaMember, ProxyInfo> cache)
      implements JsonStreamFactory {
    @Override
    public <T> T ofRoot(Class<T> objType, IntSupplier in) {
      return JsonStream.ofRoot(objType, in, toJava, cache);
    }

    @Override
    public <T> Stream<T> of(Class<T> streamType, IntSupplier in) {
      return JsonStream.of(streamType, in, toJava, cache);
    }
  }

  public static JsonStreamFactory createFactory(JsonToJava toJava) {
    return toJava == JsonToJava.DEFAULT
        ? DEFAULT__MAPPING_FACTORY
        : new Factory(toJava, new ConcurrentHashMap<>());
  }

  public static <T> T ofRoot(Class<T> objType, IntSupplier in) {
    return ofRoot(objType, in, JsonToJava.DEFAULT);
  }

  public static <T> T ofRoot(Class<T> objType, IntSupplier in, JsonToJava toJava) {
    return ofRoot(objType, in, toJava, new IdentityHashMap<>());
  }

  @SuppressWarnings("unchecked")
  private static <T> T ofRoot(
      Class<T> objType, IntSupplier in, JsonToJava toJava, Map<JavaMember, ProxyInfo> cache) {
    JsonStream handler = new JsonStream(in, toJava, cache);
    JavaMember root = JavaMember.newRootMember(PROXY_OBJECT, objType, objType);
    JsonFrame frame = handler.pushFrame(root);
    return (T) frame.proxy;
  }

  public static <T> Stream<T> of(Class<T> streamType, IntSupplier in) {
    return of(streamType, in, JsonToJava.DEFAULT);
  }

  public static <T> Stream<T> of(Class<T> streamType, IntSupplier in, JsonToJava toJava) {
    return of(streamType, in, toJava, new IdentityHashMap<>());
  }

  private static <T> Stream<T> of(
      Class<T> streamType, IntSupplier in, JsonToJava toJava, Map<JavaMember, ProxyInfo> cache) {
    JavaMember member = JavaMember.newRootMember(PROXY_STREAM, Stream.class, streamType);
    JsonStream handler = new JsonStream(in, toJava, cache);
    @SuppressWarnings("unchecked")
    Stream<T> res = (Stream<T>) handler.yieldStreaming(toJava, member, new String[0]);
    return res;
  }

  private final JsonParser in;
  private final JsonToJava toJava;

  /**
   * The currently processed frame is always at index 0 - this means the top most frame of the JSON
   * structure is at the end.
   */
  private final Deque<JsonFrame> stack = new LinkedList<>();

  private final Map<JavaMember, ProxyInfo> info;

  private JsonStream(IntSupplier in, JsonToJava toJava, Map<JavaMember, ProxyInfo> info) {
    this.in = new JsonParser(in, this::toString);
    this.toJava = toJava;
    this.info = info;
  }

  @Override
  public String toString() {
    StringBuilder str = new StringBuilder();
    List<JsonFrame> frames = new ArrayList<>();
    stack.descendingIterator().forEachRemaining(frames::add);
    String indent = "";
    boolean isRootArray = !frames.isEmpty() && frames.get(0).proxyStreamItemNo >= 0;
    if (isRootArray) {
      str.append("[... <").append(frames.get(0).proxyStreamItemNo).append(">\n");
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
        String no = notLastFrame ? "" + (frames.get(i + 1).proxyStreamItemNo) : "?";
        JavaMember member = f.info.getMemberByJsonName(name);
        if (member == null) continue;
        if (!member.processingType().isStreaming()) {
          str.append(indent).append("\t\"").append(name).append("\": ").append(v).append(",\n");
        } else {
          str.append(indent).append("\t").append('"').append(name);
          // is this a member that is currently streamed by next frame?
          if (notLastFrame && name.equals(frames.get(i + 1).info.of.jsonName())) {
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

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Class<?> declaringClass = method.getDeclaringClass();
    if (method.isDefault()) {
      throw new UnsupportedOperationException("Default methods cannot be used on proxies yet.");
    }
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
    readSimpleMembersAndSuspend(frame);
    return yieldValue(frame, JavaMember.getMember(method), args);
  }

  private Object yieldValue(JsonFrame frame, JavaMember member, Object[] args) {
    if (member.processingType().isSuspending()) {
      if (!member.jsonName().equals(frame.suspendedAtMember)) {
        frame.checkNotAlreadyProcessed(member);
        // assume the input does not contain the requested member so its value is empty
        frame.markAsProcessed(member, -1);
        return switch (member.processingType()) {
          case MAPPED_STREAM, PROXY_STREAM -> Stream.empty();
          case MAPPED_ITERATOR, PROXY_ITERATOR -> emptyIterator();
          default -> null; // = void for consumers
        };
      }
      frame.markAsProcessed(member, 0);
      // what we have is what we now are going to handle so afterwards we could do next one
      frame.suspendedAtMember = null;
      return yieldStreaming(toJava, member, args);
    }
    if (member.processingType() == RAW_VALUES) return frame.getAnyOtherValue();
    return toJavaType(frame.info, member, frame.getValue(member), args);
  }

  private Object yieldStreaming(JsonToJava toJava, JavaMember member, Object[] args) {
    int cp = in.readCharSkipWhitespace();
    if (cp == 'n') { // streaming member is declared JSON null
      in.skipCodePoints(3); // ull
      return member.nulls().hasDefaultParameter()
          ? args[member.getDefaultValueParameterIndex()]
          : toJava
              .mapTo(member.types().returnType())
              .mapNull(); // TODO should be in nulls already? => no there we have null for valueType
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
        case MAPPED_CONSUMER -> arrayViaMappedConsumer(member, args);
        case MAPPED_ITERATOR -> arrayAsMappedIterator(member, args);
        case MAPPED_STREAM -> arrayAsMappedStream(member, args);
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
      case MAPPED_CONSUMER -> objectAsMappedEntryConsumer(member, args);
      case MAPPED_ITERATOR -> objectAsMappedEntryIterator(member, args);
      case MAPPED_STREAM -> objectAsMappedEntryStream(member, args);
      default -> throw new UnsupportedOperationException("stream of " + member.processingType());
    };
  }

  private void readSimpleMembersAndSuspend(JsonFrame frame) {
    if (frame.suspendedAtMember != null || frame.isClosed) return;
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
        frame.isClosed = true;
        return;
      }
      if (cp != '"') throw formatException(cp, '"', '}');
      String name = in.readString();
      in.readCharSkipWhitespace(':');
      JavaMember member = frame.info.getMemberByJsonName(name);
      if (member == null) {
        if (frame.info.anyOtherValueMember != null) {
          cp = in.readNodeDetect(val -> frame.addAnyOtherValue(name, val));
        } else {
          // input has a member that is not mapped to java, we ignore it
          cp = in.skipNodeDetect();
        }
        frame.suspendedAtMember = null;
        continue;
      } else if (member.processingType().isSuspending()) {
        frame.checkNotAlreadyProcessed(member);
        frame.suspendedAtMember = member.jsonName();
        frame.isSuspended = true;
        return;
      }
      frame.suspendedAtMember = null;
      // MAPPED_VALUE
      // TODO are we accepting the type of the value?
      // TODO also: limit size of collections
      cp = in.readNodeDetect(val -> frame.setValue(member, val));
    }
    frame.isClosed = true;
    // need to end frame of single proxy objects (unless it is the root)
    if (frame.info.of.processingType() == PROXY_OBJECT && stack.size() > 1) popFrame(frame.info.of);
  }

  private static Object toJavaType(ProxyInfo info, JavaMember member, Object value, Object[] args) {
    if (value == null)
      return member.nulls().hasDefaultParameter()
          ? args[member.getDefaultValueParameterIndex()]
          : info.nullValues[member.index()];
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
    // TODO make this if-type-switch flexible?
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
      frame.nextProxyInStream();
      frame.setValue(null, key);
      consumer.accept(frame.proxy);
      cp = in.readCharSkipWhitespace();
    }
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
      frame.nextProxyInStream();
      frame.isOpened = true;
      consumer.accept(frame.proxy);
      cp = in.readCharSkipWhitespace();
    }
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
          frame.isClosed = true;
          popFrame(member);
          return false;
        }
        frame.nextProxyInStream();
        if (frame.proxyStreamItemNo > 0) {
          if (cp != ',') throw formatException(cp, ',', '}');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '"') throw formatException(cp, '"');
        return true;
      }

      @Override
      public Object next() {
        if (frame.isClosed) throw new NoSuchElementException("" + frame.proxyStreamItemNo);
        // opening " already consumed in hasNext
        String key = in.readString(); // includes closing "
        in.readCharSkipWhitespace(':');
        frame.setValue(null, key);
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
          frame.isClosed = true;
          popFrame(member);
          return false;
        }
        frame.nextProxyInStream();
        if (frame.proxyStreamItemNo > 0) {
          if (cp != ',') throw formatException(cp, ',', ']');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '{') throw formatException(cp, ']', '{');
        return true;
      }

      @Override
      public Object next() {
        if (frame.isClosed) throw new NoSuchElementException("" + frame.proxyStreamItemNo);
        frame.isOpened = true; // { already read by hasNext
        return frame.proxy;
      }
    };
  }

  private Void arrayViaMappedConsumer(JavaMember member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Object> consumer = (Consumer<Object>) args[0];
    arrayAsMappedIterator(member, args).forEachRemaining(consumer);
    return null; // = void
  }

  private Stream<?> arrayAsMappedStream(JavaMember member, Object[] args) {
    return toStream(arrayAsMappedIterator(member, args));
  }

  private Iterator<?> arrayAsMappedIterator(JavaMember member, Object[] args) {
    JsonFrame frame = currentFrame();
    frame.mappedStreamItemNo = -1;
    return new Iterator<>() {
      final Class<?> keyType = member.types().keyType();
      int cp = ','; // [ already consumed, pretend using ','

      @Override
      public boolean hasNext() {
        if (cp == ']') {
          frame.markAsProcessed(member, frame.mappedStreamItemNo);
          return false;
        }
        frame.nextMappedValueInStream();
        if (frame.mappedStreamItemNo > 0 && cp != ',') throw formatException(cp, ',', ']');
        return true;
      }

      @Override
      public Object next() {
        if (cp == ']') throw new NoSuchElementException("" + frame.mappedStreamItemNo);
        // reading value and either , or ]
        cp =
            in.readNodeDetect(
                val -> frame.setValue(member, toJavaType(frame.info, member, val, args)));
        return keyType == null
            ? frame.getValue(member)
            : new SimpleEntry<>(
                toSimpleJavaType(frame.info.getJsonToKey(member), frame.mappedStreamItemNo),
                frame.getValue(member));
      }
    };
  }

  private Void objectAsMappedEntryConsumer(JavaMember member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Entry<?, ?>> consumer = (Consumer<Entry<?, ?>>) args[0];
    objectAsMappedEntryIterator(member, args).forEachRemaining(consumer);
    return null; // = void
  }

  private Stream<Entry<?, ?>> objectAsMappedEntryStream(JavaMember member, Object[] args) {
    return toStream(objectAsMappedEntryIterator(member, args));
  }

  private Iterator<Entry<?, ?>> objectAsMappedEntryIterator(JavaMember member, Object[] args) {
    JsonFrame frame = currentFrame();
    return new Iterator<>() {
      final Class<?> keyType = member.types().keyType();
      int cp = in.readCharSkipWhitespace();

      @Override
      public boolean hasNext() {
        if (cp == '}') {
          frame.markAsProcessed(member, frame.mappedStreamItemNo);
          return false;
        }
        frame.nextMappedValueInStream();
        if (frame.mappedStreamItemNo > 0) {
          if (cp != ',') throw formatException(cp, ',', '}');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '"') throw formatException(cp, '"');
        return true;
      }

      @Override
      public Entry<?, ?> next() {
        if (cp == '}') throw new NoSuchElementException("" + frame.mappedStreamItemNo);
        // opening " already consumed in hasNext
        String key = in.readString();
        in.readCharSkipWhitespace(':');
        // reading value and either , or }
        cp =
            in.readNodeDetect(
                val -> frame.setValue(member, toJavaType(frame.info, member, val, args)));
        return new SimpleEntry<>(
            toSimpleJavaType(frame.info.getJsonToKey(member), key), frame.getValue(member));
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
    JsonFrame frame = currentFrame();
    if (frame != null) frame.markAsProcessed(member, done.proxyStreamItemNo + 1);
  }

  private JsonFrame pushFrame(JavaMember member) {
    Class<?> type = member.types().valueType();
    JsonFrame frame =
        new JsonFrame(
            info.computeIfAbsent(
                member, m -> new ProxyInfo(m, JavaMember.getMembersOf(type), toJava)),
            newProxy(type, this));
    stack.addFirst(frame);
    return frame;
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

    private final JsonToJava toJava;
    /** The java member (or type) the frames represent */
    private final JavaMember of;
    /** Values for JSON null or undefined for each member by {@link JavaMember#index()} */
    private final Object[] nullValues;
    /** Converter to use for each member value by by {@link JavaMember#index()} */
    private final JsonTo<?>[] jsonToValueTypes;
    /** Converter to use for each member key (in map values) by by {@link JavaMember#index()} */
    private final JsonTo<?>[] jsonToKeyTypes;

    private final Map<String, JavaMember> membersByJsonName;
    private final JavaMember anyOtherValueMember;

    ProxyInfo(JavaMember of, Map<String, JavaMember> membersByJsonName, JsonToJava toJava) {
      this.toJava = toJava;
      this.of = of;
      int size = membersByJsonName.size() + 1;
      this.membersByJsonName = membersByJsonName;
      this.nullValues = new Object[size];
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
      jsonToValueTypes[i] = m.jsonToValueType(toJava);
      jsonToKeyTypes[i] = m.jsonToKeyType(toJava);
      JsonTo<?> nullToJava = toJava.mapTo(m.nullValueType());
      JavaMember.Nulls nulls = m.nulls();
      nullValues[i] =
          nulls.jsonDefaultValue() == null
              ? nulls.retainNull() ? null : nullToJava.mapNull()
              : toJavaType(this, m, nulls.jsonDefaultValue(), new Object[0]);
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

    final ProxyInfo info;

    final Object proxy;

    /**
     * The values of members read so far, for streaming members this holds the number of streamed
     * items once the member is done.
     *
     * <p>Index 0 is always for the key member, if no such member exist first value is at index 1
     */
    private final Object[] values;

    /**
     * For each value (same index as {@link #values}) this remembers the order the value appeared in
     * the input
     */
    private final int[] memberInputOrder;

    /** Members read so far */
    private int memberInputCount;

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

    int proxyStreamItemNo = -1;

    int mappedStreamItemNo = -1;

    private JsonFrame(ProxyInfo info, Object proxy) {
      this.info = info;
      this.proxy = proxy;
      int size = info.membersByJsonName.size() + 1;
      this.values = new Object[size];
      this.memberInputOrder = new int[size];
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
        if (values[i] != null)
          entries[memberInputOrder[i]] = new SimpleEntry<>(m.jsonName(), values[i]);
      }
      return Arrays.stream(entries).filter(Objects::nonNull).iterator();
    }

    void setValue(JavaMember member, Object value) {
      int index = member == null ? 0 : member.index();
      values[index] = value;
      memberInputOrder[index] = memberInputCount++;
    }

    void addAnyOtherValue(String name, Object value) {
      int index = info.anyOtherValueMember.index();
      @SuppressWarnings("unchecked")
      Map<String, Object> rawValues = (Map<String, Object>) values[index];
      if (rawValues == null) {
        rawValues = new HashMap<>();
        values[index] = rawValues;
      }
      rawValues.put(name, value);
    }

    Object getValue(JavaMember member) {
      return values[member.index()];
    }

    Map<String, ?> getAnyOtherValue() {
      @SuppressWarnings("unchecked")
      Map<String, ?> rawValues =
          info.anyOtherValueMember == null
              ? null
              : (Map<String, ?>) values[info.anyOtherValueMember.index()];
      return rawValues == null ? Map.of() : unmodifiableMap(rawValues);
    }

    void markAsProcessed(JavaMember member, int itemNo) {
      setValue(member, itemNo);
    }

    void nextProxyInStream() {
      proxyStreamItemNo++;
      checkConstraintMaxOccur(proxyStreamItemNo);
      memberInputCount = 0;
      mappedStreamItemNo = -1;
      isOpened = false;
      isClosed = false;
      isSuspended = false;
      suspendedAtMember = null;
      arraycopy(info.nullValues, 0, values, 0, values.length);
      fill(memberInputOrder, 0);
    }

    void nextMappedValueInStream() {
      mappedStreamItemNo++;
      checkConstraintMaxOccur(mappedStreamItemNo);
    }

    private void checkConstraintMaxOccur(int occur) {
      JavaMember of = info.of;
      int maxOccur = of.constraints().maxOccur();
      if (occur >= maxOccur) throw maxOccurExceeded(of.types().valueType(), maxOccur);
    }

    void checkNotAlreadyProcessed(JavaMember member) {
      if (values[member.index()] != null)
        throw JsonSchemaException.outOfOrder(
            member.jsonName(),
            info.membersByJsonName.values().stream()
                .filter(m -> m.processingType().isStreaming())
                .filter(m -> m != member)
                .filter(m -> values[m.index()] != null)
                .map(JavaMember::jsonName));
    }
  }

  private JsonFormatException formatException(int found, char... expected) {
    return unexpectedInputCharacter(found, this::toString, expected);
  }
}
