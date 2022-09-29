package se.jbee.json.stream;

import se.jbee.json.stream.JsonMapping.JsonTo;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.Character.isUpperCase;
import static java.lang.Character.toLowerCase;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyIterator;
import static java.util.Spliterators.spliteratorUnknownSize;
import static se.jbee.json.stream.JsonFormatException.unexpectedInputCharacter;
import static se.jbee.json.stream.JsonSchemaException.maxOccurExceeded;
import static se.jbee.json.stream.JsonStream.MemberType.*;

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

  public static IntSupplier from(InputStream in) {
    return () -> {
      try {
        return in.read();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    };
  }

  public static IntSupplier from(Reader in) {
    return () -> {
      try {
        return in.read();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    };
  }

  public static <T> T ofRoot(Class<T> objType, IntSupplier in) {
    return ofRoot(objType, in, JsonMapping.auto());
  }

  @SuppressWarnings("unchecked")
  public static <T> T ofRoot(Class<T> objType, IntSupplier in, JsonMapping mapping) {
    JsonStream handler = new JsonStream(in, mapping);
    Member root = new Member(PROXY_OBJECT, "", 1, objType, null, null, false, false, 0, Integer.MAX_VALUE);
    JsonFrame frame = handler.pushFrame(root);
    return (T) frame.proxy;
  }

  public static <T> Stream<T> of(Class<T> streamType, IntSupplier in) {
    return of(streamType, in, JsonMapping.auto());
  }

  public static <T> Stream<T> of(Class<T> streamType, IntSupplier in, JsonMapping mapping) {
    int max = Integer.MAX_VALUE;
    Member member =
        new Member(
            PROXY_STREAM, "", 1, Stream.class, streamType, Stream.empty(), false, false, 0, max);
    JsonStream handler = new JsonStream(in, mapping);
    @SuppressWarnings("unchecked")
    Stream<T> res = (Stream<T>) handler.yieldStreaming(member, new String[0]);
    return res;
  }

  private <T> Map<String, Member> initStreamType(Class<T> type) {
    if (!type.isInterface())
      throw new IllegalArgumentException(
          "Stream must be mapped to an interface returnType but got: " + type);
    return MEMBERS_BY_TYPE.computeIfAbsent(
        type,
        key -> {
          Map<String, Member> membersByName = new HashMap<>();
          int n = 1;
          for (Method m : key.getMethods()) {
            if (!m.isDefault() && !m.isSynthetic() && !Modifier.isStatic(m.getModifiers())) {
              String name = Member.name(m);
              int index = membersByName.containsKey(name) ? membersByName.get(name).index : n++;
              Member member = new Member(m, index);
              MEMBERS_BY_METHOD.put(m, member);
              if (!membersByName.containsKey(name) || m.getParameterCount() == 0) {
                membersByName.put(name, member);
              }
            }
          }
          return membersByName;
        });
  }

  /**
   * As all classes whose methods we put in here are interfaces where is no risk of any memory
   * leaks. This can just be shared in the JVM.
   */
  private static final Map<Method, Member> MEMBERS_BY_METHOD = new ConcurrentHashMap<>();

  private static final Map<Class<?>, Map<String, Member>> MEMBERS_BY_TYPE =
      new ConcurrentHashMap<>();

  private final JsonReader in;
  private final JsonMapping mapping;
  private final Map<Class<?>, JsonTo<?>> jsonToAny = new HashMap<>();
  /**
   * The currently processed frame is always at index 0 - this means the top most frame of the JSON
   * structure is at the end.
   */
  private final Deque<JsonFrame> stack = new LinkedList<>();

  private JsonStream(IntSupplier in, JsonMapping mapping) {
    this.in = new JsonReader(in, this::toString);
    this.mapping = mapping;
  }

  @Override
  public String toString() {
    StringBuilder str = new StringBuilder();
    List<JsonFrame> frames = new ArrayList<>();
    stack.descendingIterator().forEachRemaining(frames::add);
    String indent = "";
    boolean isRootArray = !frames.isEmpty() && frames.get(0).itemNo >= 0;
    if (isRootArray) {
      str.append("[... <").append(frames.get(0).itemNo).append(">\n");
      indent += '\t';
    }
    for (int i = 0; i < frames.size(); i++) {
      JsonFrame f = frames.get(i);
      if (!f.isOpened) continue;
      str.append(indent).append("{\n");
      for (Entry<String, Serializable> value : f) {
        Serializable v = value.getValue();
        String name = value.getKey();
        boolean notLastFrame = i < frames.size() - 1;
        String no = notLastFrame ? "" + (frames.get(i + 1).itemNo) : "?";
        Member member = f.members.get(name);
        if (member == null) continue;
        if (!member.isStreaming()) {
          str.append(indent).append("\t\"").append(name).append("\": ").append(v).append(",\n");
        } else {
          str.append(indent).append("\t").append('"').append(name);
          if (notLastFrame && name.equals(frames.get(i+1).of.name)) { // is this a member that is currently streamed by next frame?
            str.append("\": [... <").append(no).append(">\n");
          } else
            str.append("\": [")
                .append(Integer.valueOf(-1).equals(v) ? "..." : "<" + v + ">")
                .append("],\n");
        }
      }
      if (f.streamingMember != null) str.append("<" + f.streamingMember + "?>");
      if (f.isClosed) str.append(indent).append("}\n");
      indent += "\t";
    }
    if (!frames.isEmpty() && !frames.get(0).isClosed) {
      int unclosedFrames = (int) frames.stream().filter(f -> !f.isClosed).count();
      str.append("\t".repeat(Math.max(0, unclosedFrames - 1))).append("<stream position>");
    }
    return str.toString();
  }

  private JsonTo<?> jsonTo(Class<?> to) {
    return jsonToAny.computeIfAbsent(to, mapping::mapTo);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Class<?> declaringClass = method.getDeclaringClass();
    if (method.isDefault()) {
      return MethodHandles.lookup()
          .findSpecial(
              declaringClass,
              method.getName(),
              MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
              declaringClass)
          .bindTo(proxy)
          .invokeWithArguments(args);
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
    readMembers(frame);
    return yieldValue(frame, MEMBERS_BY_METHOD.get(method), args);
  }

  private Object yieldValue(JsonFrame frame, Member member, Object[] args) {
    if (member.isPausing()) {
      if (!member.name.equals(frame.streamingMember)) {
        frame.checkNotAlreadyProcessed(member);
        // assume the input does not contain the requested member so its value is empty
        frame.markAsProcessed(member, -1);
        return switch (member.type) {
          case MAPPED_STREAM, PROXY_STREAM -> Stream.empty();
          case MAPPED_ITERATOR, PROXY_ITERATOR -> emptyIterator();
          default -> null; // = void for consumers
        };
      }
      frame.markAsProcessed(member, 0);
      // what we have is what we now are going to handle so afterwards we could do next one
      frame.streamingMember = null;
      return yieldStreaming(member, args);
    } else if (member.type == PROXY_OBJECT) {
      return frame.proxy; //TODO maybe makes no sense, frame is not member
      /*
      JsonStream h = new JsonStream(() -> -1, mapping);
      JsonFrame f1 = h.pushFrame(member, (Map<String, Serializable>) frame.directValue(name));
      f1.isOpened = true;
      f1.isClosed = true;
      return f1.proxy;
      */
    }
    //TODO use member not name?
    return yieldDirect(member, frame.directValue(member.name()), args);
  }

  private Object yieldDirect(Member member, Object value, Object[] args) {
    if (value == null) return member.providesDefault() ? args[0] : member.nullValue();
    Class<?> as = member.returnType();
    if (value instanceof String s) return as == String.class ? s : jsonTo(as).mapString().apply(s);
    if (value instanceof Boolean b)
      return as == boolean.class || as == Boolean.class ? b : jsonTo(as).mapBoolean().apply(b);
    if (value instanceof Number n) return yieldAsNumber(as, n);
    if (value instanceof List<?> list) return yieldAsCollection(member, list, args);
    throw new UnsupportedOperationException("JSON value not supported: " + value);
  }

  /**
   * A JSON number is mapped to a java {@link Number} subtype
   */
  private Object yieldAsNumber(Class<?> as, Number n) {
    if (as == Number.class) return n;
    if (as == int.class || as == Integer.class) return n.intValue();
    if (as == long.class || as == Long.class) return n.longValue();
    if (as == float.class || as == Float.class) return n.floatValue();
    if (as == double.class || as == Double.class) return n.doubleValue();
    if (as.isInstance(n)) return n;
    return jsonTo(as).mapNumber().apply(n);
  }

  /**
   * A JSON array is mapped to a java collection subtype.
   * The elements should be "simple" mapped values.
   */
  private Object yieldAsCollection(Member member, List<?> list, Object[] args) {
    //TODO
    return null;
  }

  private Object yieldStreaming(Member member, Object[] args) {
    int cp = in.readCharSkipWhitespace();
    if (cp != '[' && cp != '{') throw formatException(cp, '[', '{');
    if (cp == '[') {
      // stream from array
      return switch (member.type) {
        case PROXY_STREAM -> arrayAsProxyStream(member);
        case PROXY_ITERATOR -> arrayAsProxyIterator(member);
        case PROXY_CONSUMER -> arrayViaProxyConsumer(member, args);
        case MAPPED_CONSUMER -> arrayViaMappedConsumer(member, args);
        // TODO consider non proxy streaming
        default -> throw new UnsupportedOperationException("stream of " + member.type);
      };
    }
    // stream from object
    return switch (member.type) {
      case PROXY_OBJECT -> objectAsProxy(member);
      case PROXY_STREAM -> objectAsProxyStream(member);
      case PROXY_ITERATOR -> objectAsProxyIterator(member);
      case PROXY_CONSUMER -> objectViaProxyConsumer(member, args);
      case MAPPED_CONSUMER -> objectViaMappedConsumer(member, args);
      // TODO consider non proxy streaming
      default -> throw new UnsupportedOperationException("stream of " + member.type);
    };
  }

  private void readMembers(JsonFrame frame) {
    if (frame.streamingMember != null || frame.isClosed) return;
    int cp = ',';
    if (!frame.isOpened) {
      in.readCharSkipWhitespace('{');
      frame.isOpened = true;
    } else if (frame.isPaused) {
      cp = in.readCharSkipWhitespace(); // should be , or }
      frame.isPaused = false;
    }
    while (cp != '}') {
      if (cp != ',') throw formatException(cp, ',', '}');
      in.readCharSkipWhitespace('"');
      String name = in.readString();
      in.readCharSkipWhitespace(':');
      Member member = frame.members.get(name);
      if (member == null) {
        // input has a member that is not mapped to java, we ignore it
        cp = in.skipNodeDetect();
        frame.streamingMember = null;
        continue;
      } else if (member.isPausing()) {
        frame.checkNotAlreadyProcessed(member);
        frame.streamingMember = member.name;
        frame.isPaused = true;
        return;
      }
      frame.streamingMember = null;
      // MAPPED_VALUE
      cp = in.readNodeDetect(val -> frame.setDirectValue(member, val));
    }
    frame.isClosed = true;
    // need to end frame of single proxy objects (unless it is the root)
    if (frame.of.type == PROXY_OBJECT && stack.size() > 1)
      popFrame(frame.of);
  }

  private Void objectViaMappedConsumer(Member member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Entry<?, ?>> consumer = (Consumer<Entry<?, ?>>) args[0];
    // TODO add mapping to consumer when needed
    int c = 0;
    int cp = ',';
    while (cp != '}') {
      if (cp != ',') throw formatException(cp, ',', '}');
      in.readCharSkipWhitespace('{');
      in.readCharSkipWhitespace('"');
      String key = in.readString();
      in.readCharSkipWhitespace(':');
      SimpleEntry<String, Object> entry = new SimpleEntry<>(key, null);
      cp = in.readNodeDetect(entry::setValue);
      c++;
    }
    currentFrame().markAsProcessed(member, c);
    return null;
  }

  private Void objectViaProxyConsumer(Member member, Object[] args) {
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
      frame.nextInStream();
      frame.setDirectValue(null, key);
      consumer.accept(frame.proxy);
      cp = in.readCharSkipWhitespace();
    }
    popFrame(member);
    return null;
  }

  private Void arrayViaMappedConsumer(Member member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Serializable> consumer = (Consumer<Serializable>) args[0];
    int c = 0;
    int cp = ',';
    while (cp != ']') {
      if (cp != ',') throw formatException(cp, ',', ']');
      // TODO add mapping to consumer target type when needed (now it only supports direct JSON => java types)
      cp = in.readNodeDetect(consumer);
      c++;
    }
    currentFrame().markAsProcessed(member, c);
    return null; // = void
  }

  private Void arrayViaProxyConsumer(Member member, Object[] args) {
    JsonFrame frame = pushFrame(member);
    @SuppressWarnings("unchecked")
    Consumer<Object> consumer = (Consumer<Object>) args[0];
    int cp = ',';
    while (cp != ']') {
      if (cp != ',') throw formatException(cp, ',', ']');
      in.readCharSkipWhitespace('{');
      frame.nextInStream();
      frame.isOpened = true;
      consumer.accept(frame.proxy);
      cp = in.readCharSkipWhitespace();
    }
    popFrame(member);
    return null; // = void
  }

  private Object objectAsProxy(Member member) {
    JsonFrame frame = pushFrame(member);
    frame.isOpened = true;
    return frame.proxy;
  }

  private Stream<?> objectAsProxyStream(Member member) {
    return toStream(objectAsProxyIterator(member));
  }

  private Iterator<?> objectAsProxyIterator(Member member) {
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
        frame.nextInStream();
        if (frame.itemNo > 0) {
          if (cp != ',') throw formatException(cp, ',', '}');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '"') throw formatException(cp, '"');
        // at the end we always have read the opening double quote of the member name already
        return true;
      }

      @Override
      public Object next() {
        if (frame.isClosed) throw new NoSuchElementException("" + frame.itemNo);
        String key = in.readString(); // includes closing "
        in.readCharSkipWhitespace(':');
        frame.setDirectValue(null, key);
        return frame.proxy;
      }
    };
  }

  private Stream<?> arrayAsProxyStream(Member member) {
    return toStream(arrayAsProxyIterator(member));
  }

  private Iterator<?> arrayAsProxyIterator(Member member) {
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
        frame.nextInStream();
        if (frame.itemNo > 0) {
          if (cp != ',') throw formatException(cp, ',', ']');
          cp = in.readCharSkipWhitespace();
        }
        if (cp != '{') throw formatException(cp, ']', '{');
        return true;
      }

      @Override
      public Object next() {
        if (frame.isClosed) throw new NoSuchElementException("" + frame.itemNo);
        frame.isOpened = true; // { already read by hasNext
        return frame.proxy;
      }
    };
  }

  private static Stream<?> toStream(Iterator<?> iter) {
    return StreamSupport.stream(spliteratorUnknownSize(iter, STREAM_CHARACTERISTICS), false);
  }

  private JsonFrame currentFrame() {
    return stack.peekFirst();
  }

  private void popFrame(Member member) {
    JsonFrame done = stack.removeFirst();
    JsonFrame frame = currentFrame();
    if (frame != null) frame.markAsProcessed(member, done.itemNo + 1);
  }

  private JsonFrame pushFrame(Member member) {
    Class<?> type = member.isStreaming() ? member.itemType() : member.returnType();
    JsonFrame frame = new JsonFrame(member, newProxy(type, this), initStreamType(type));
    stack.addFirst(frame);
    return frame;
  }

  private static Object newProxy(Class<?> type, JsonStream handler) {
    return Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, handler);
  }

  /** Holds the information and parse state for JSON input for a single JSON object level. */
  private static class JsonFrame implements Iterable<Entry<String,Serializable>> {
    final Member of;
    final Object proxy;

    final Map<String, Member> members;
    /**
     * The values of members read so far, for streaming members this holds the number of streamed
     * items once the member is done.
     *
     * Index 0 is always for the key member, if no such member exist first value is at index 1
     */
    private final Serializable[] values;
    /**
     * For each value (same index as {@link #values}) this remembers the order the value appeared in the input
     */
    private final int[] memberInputOrder;
    /**
     * Members read so far
     */
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
    boolean isPaused;

    int itemNo = -1;

    /**
     * A {@link Member#isStreaming()} input that is not yet been processed - if set we are at the
     * start of that member value in the input stream
     */
    String streamingMember;

    private JsonFrame(Member of, Object proxy, Map<String, Member> members) {
      this.of = of;
      this.proxy = proxy;
      this.members = members;
      int size = members.size() + 1;
      this.values = new Serializable[size];
      this.memberInputOrder = new int[size];
    }

    /**
     * @return the current values of this frame by member name
     */
    @Override
    public Iterator<Entry<String, Serializable>> iterator() {
      Entry<String, Serializable>[] entries = new Entry[memberInputOrder.length];
      for (Member m : members.values()) {
        int i = m.index();
        if (values[i] != null)
          entries[memberInputOrder[i]] = new SimpleEntry<>(m.name, values[i]);
      }
      return Arrays.stream(entries).filter(Objects::nonNull).toList().iterator();
    }

    void setDirectValue(Member member, Serializable value) {
      int index = member == null ? 0 : member.index;
      values[index] = value;
      memberInputOrder[index] = memberInputCount++;
    }

    Serializable directValue(String member) {
      return values[members.get(member).index];
    }

    void markAsProcessed(Member member, int itemNo) {
      setDirectValue(member, itemNo);
    }

    void nextInStream() {
      itemNo++;
      if (itemNo >= of.maxOccur()) throw maxOccurExceeded(of.itemType(), of.maxOccur());
      memberInputCount = 0;
      isOpened = false;
      isClosed = false;
      isPaused = false;
      streamingMember = null;
      fill(values, null);
      fill(memberInputOrder, 0);
    }

    void checkNotAlreadyProcessed(Member member) {
      if (values[member.index] != null)
        throw JsonSchemaException.outOfOrder(member.name,
            members.values().stream()
                .filter(Member::isStreaming)
                .filter(m -> m != member)
                .filter(m -> values[m.index] != null)
                .map(m -> m.name));
    }
  }

  /**
   * Based on the type information extracted from user defined target interfaces each object member
   * is processed in one of the ways described below.
   *
   * <p>This is purely to only have to analyse the type of processing needed once and then remember
   * it. This could have otherwise been derived from a proxied {@link Method} each time it is
   * called.
   */
  enum MemberType {
    /** JSON value is directly mapped to the Java type */
    MAPPED_VALUE,
    /**
     * JSON array or object consumed as Java {@link Stream} where each value is mapped directly from
     * a JSON value to a Java value
     */
    MAPPED_STREAM,
    /**
     * JSON array or object consumed as Java {@link Iterator} where each value is mapped directly
     * from a JSON value to a Java value
     */
    MAPPED_ITERATOR,
    /**
     * JSON array or object consumed as Java {@link Consumer} where each value is mapped directly
     * from a JSON value to a Java value
     */
    MAPPED_CONSUMER,
    /** JSON object consumed in Java via {@link Proxy} of a user defined interface */
    PROXY_OBJECT,
    /**
     * JSON array or object consumed as Java {@link Stream} of {@link Proxy} instances of a user
     * defined interface
     */
    PROXY_STREAM,
    /**
     * JSON array or object consumed as Java {@link Iterator} of {@link Proxy} instances of a user
     * defined interface
     */
    PROXY_ITERATOR,
    /**
     * JSON array or object consumed as Java {@link Consumer} of {@link Proxy} instances of a user
     * defined interface
     */
    PROXY_CONSUMER
  }

  /**
   * Holds the "meta" information on a member {@link Method} of a {@link Stream} interface
   * returnType.
   *
   * @param returnType root returnType
   * @param itemType of the items used in a stream for this member - only set if this is streaming or collection
   * @param providesDefault true in case the method represented by this member has a default
   *     argument to return in case the member is not present or given as JSON null
   */
  private record Member(
      MemberType type,
      String name,
      int index,
      Class<?> returnType,
      Class<?> itemType,
      Object nullValue,
      boolean providesDefault,
      boolean isMapKey,
      int minOccur,
      int maxOccur) {

    public Member(Method m, int index) {
      this(
          type(m),
          name(m),
          isMapKey(m) ? 0 : index,
          m.getReturnType(),
          itemType(m),
          nullValue(m),
          providesDefault(m),
          isMapKey(m),
          minOccur(m),
          maxOccur(m));
    }

    boolean isStreaming() {
      return type != MAPPED_VALUE && type != PROXY_OBJECT;
    }

    boolean isPausing() {
      return type != MAPPED_VALUE;
    }

    private static MemberType type(Method m) {
      Class<?> as = m.getReturnType();
      if (as == Stream.class)
        return isProxyInterface(actualTypeGeneric(m.getGenericReturnType()))
            ? PROXY_STREAM
            : MAPPED_STREAM;
      if (as == Iterator.class)
        return isProxyInterface(actualTypeGeneric(m.getGenericReturnType()))
            ? PROXY_ITERATOR
            : MAPPED_ITERATOR;
      if (as == void.class
          && m.getParameterCount() == 1
          && m.getParameterTypes()[0] == Consumer.class)
        return isProxyInterface(actualTypeGeneric(m.getGenericParameterTypes()[0]))
            ? PROXY_CONSUMER
            : MAPPED_CONSUMER;
      return isProxyInterface(as) ? PROXY_OBJECT : MAPPED_VALUE;
    }

    private static String name(Method m) {
      JsonProperty member = m.getAnnotation(JsonProperty.class);
      String name = m.getName();
      if (name.startsWith("get") && name.length() > 3 && isUpperCase(name.charAt(3))) {
        name = toLowerCase(name.charAt(3)) + name.substring(4);
      } else if (name.startsWith("is") && name.length() > 2 && isUpperCase(name.charAt(2)))
        name = toLowerCase(name.charAt(2)) + name.substring(3);
      if (member == null) return name;
      if (!member.name().isEmpty()) return member.name();
      return name;
    }

    private static Class<?> itemType(Method m) {
      return switch (type(m)) {
        case MAPPED_VALUE, PROXY_OBJECT -> null;
        case PROXY_CONSUMER, MAPPED_CONSUMER -> actualTypeGeneric(m.getGenericParameterTypes()[0]);
        case PROXY_STREAM, PROXY_ITERATOR, MAPPED_STREAM, MAPPED_ITERATOR -> actualTypeGeneric(m.getGenericReturnType());
      };
    }

    private static boolean providesDefault(Method m) {
      return m.getParameterCount() == 1
          && m.getGenericReturnType().equals(m.getGenericParameterTypes()[0]);
    }

    private static boolean isMapKey(Method m) {
      return m.isAnnotationPresent(JsonProperty.class) && m.getAnnotation(JsonProperty.class).key()
          || m.getName().equals("key");
    }

    private static int minOccur(Method m) {
      JsonProperty member = m.getAnnotation(JsonProperty.class);
      return member == null ? 0 : Math.max(member.minOccur(), member.required() ? 1 : 0);
    }

    private static int maxOccur(Method m) {
      JsonProperty member = m.getAnnotation(JsonProperty.class);
      return member == null ? Integer.MAX_VALUE : Math.max(member.maxOccur(), minOccur(m));
    }

    private static Object nullValue(Method m) {
      Class<?> as = m.getReturnType();
      if (!as.isPrimitive()) {
        //TODO empty collections?
        return switch (type(m)) {
          case MAPPED_VALUE, PROXY_OBJECT, PROXY_CONSUMER, MAPPED_CONSUMER -> null;
          case PROXY_STREAM, MAPPED_STREAM -> Stream.empty();
          case PROXY_ITERATOR, MAPPED_ITERATOR -> emptyIterator();
        };
      }
      if (as == long.class) return 0L;
      if (as == float.class) return 0f;
      if (as == double.class) return 0d;
      if (as == boolean.class) return false;
      if (as == void.class) return null;
      return 0;
    }

    private static Class<?> actualTypeGeneric(Type type) {
      return toClassType(((ParameterizedType) type).getActualTypeArguments()[0]);
    }

    private static Class<?> toClassType(Type type) {
      if (type instanceof Class<?> c) return c;
      if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
      return null;
    }

    private static boolean isInterface(Class<?> t) {
      return t != null && t.isInterface();
    }

    private static boolean isProxyInterface(Class<?> t) {
      return isInterface(t)
          && !Map.class.isAssignableFrom(t)
          && !Collection.class.isAssignableFrom(t)
          && !Entry.class.isAssignableFrom(t);
    }
  }

  private JsonFormatException formatException(int found, char... expected) {
    return unexpectedInputCharacter(found, this::toString, expected);
  }
}
