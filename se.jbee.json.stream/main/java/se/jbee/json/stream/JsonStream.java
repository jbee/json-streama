package se.jbee.json.stream;

import static java.lang.Character.isUpperCase;
import static java.lang.Character.toLowerCase;
import static java.lang.System.arraycopy;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyIterator;
import static java.util.Spliterators.spliteratorUnknownSize;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static se.jbee.json.stream.JsonFormatException.unexpectedInputCharacter;
import static se.jbee.json.stream.JsonSchemaException.maxOccurExceeded;
import static se.jbee.json.stream.JsonStream.MemberType.*;

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
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import se.jbee.json.stream.JsonMapping.JsonTo;

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
    Member root =
        new Member(PROXY_OBJECT, "", 1, objType, null, null, objType, null, false, false, 0, Integer.MAX_VALUE);
    JsonFrame frame = handler.pushFrame(root);
    return (T) frame.proxy;
  }

  public static <T> Stream<T> of(Class<T> streamType, IntSupplier in) {
    return of(streamType, in, JsonMapping.auto());
  }

  public static <T> Stream<T> of(Class<T> streamType, IntSupplier in, JsonMapping mapping) {
    Member member =
        new Member(
            PROXY_STREAM, "", 1, Stream.class, null,null, streamType, Stream.empty(), false, false, 0, Integer.MAX_VALUE);
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
            if (!m.isDefault()
                && !m.isSynthetic()
                && !Modifier.isStatic(m.getModifiers())
                && !m.getName().equals("skip")) {
              String name = Member.name(m);
              int index = membersByName.containsKey(name) ? membersByName.get(name).index : n++;
              Member member = Member.newMember(m, index);
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
        Member member = f.getMember(name);
        if (member == null) continue;
        if (!member.memberType.isStreaming()) {
          str.append(indent).append("\t\"").append(name).append("\": ").append(v).append(",\n");
        } else {
          str.append(indent).append("\t").append('"').append(name);
          if (notLastFrame
              && name.equals(
                  frames.get(i + 1)
                      .of
                      .name)) { // is this a member that is currently streamed by next frame?
            str.append("\": [... <").append(no).append(">\n");
          } else
            str.append("\": [")
                .append(Integer.valueOf(-1).equals(v) ? "..." : "<" + v + ">")
                .append("],\n");
        }
      }
      if (f.suspendedAtMember != null) str.append("<" + f.suspendedAtMember + "?>");
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
    return yieldValue(frame, MEMBERS_BY_METHOD.get(method), args);
  }

  private Object yieldValue(JsonFrame frame, Member member, Object[] args) {
    if (member.memberType.isSuspending()) {
      if (!member.name.equals(frame.suspendedAtMember)) {
        frame.checkNotAlreadyProcessed(member);
        // assume the input does not contain the requested member so its value is empty
        frame.markAsProcessed(member, -1);
        return switch (member.memberType()) {
          case MAPPED_STREAM, PROXY_STREAM -> Stream.empty();
          case MAPPED_ITERATOR, PROXY_ITERATOR -> emptyIterator();
          default -> null; // = void for consumers
        };
      }
      frame.markAsProcessed(member, 0);
      // what we have is what we now are going to handle so afterwards we could do next one
      frame.suspendedAtMember = null;
      return yieldStreaming(member, args);
    }
    return toJavaType(member, frame.getValue(member), args);
  }

  private Object yieldStreaming(Member member, Object[] args) {
    int cp = in.readCharSkipWhitespace();
    if (cp == 'n') { // null
      in.skipCodePoints(3); //ull
      return member.providesDefault() ? args[member.getDefaultValueIndex()] : member.nullValue();
    }
    if (cp != '[' && cp != '{') throw formatException(cp, '[', '{');
    if (cp == '[') {
      // stream from array
      return switch (member.memberType()) {
        // proxy values
        case PROXY_STREAM -> arrayAsProxyStream(member);
        case PROXY_ITERATOR -> arrayAsProxyIterator(member);
        case PROXY_CONSUMER -> arrayViaProxyConsumer(member, args);
        // mapped values
        case MAPPED_CONSUMER -> arrayViaMappedConsumer(member, args);
        case MAPPED_ITERATOR -> arrayAsMappedIterator(member, args);
        case MAPPED_STREAM -> arrayAsMappedStream(member, args);
        default -> throw new UnsupportedOperationException("stream of " + member.memberType());
      };
    }
    // stream from object
    return switch (member.memberType()) {
      // proxy values
      case PROXY_OBJECT -> objectAsProxy(member);
      case PROXY_STREAM -> objectAsProxyStream(member);
      case PROXY_ITERATOR -> objectAsProxyIterator(member);
      case PROXY_CONSUMER -> objectAsProxyConsumer(member, args);
      // mapped values
      case MAPPED_CONSUMER -> objectAsMappedEntryConsumer(member, args);
      case MAPPED_ITERATOR -> objectAsMappedEntryIterator(member, args);
      case MAPPED_STREAM -> objectAsMappedEntryStream(member, args);
      default -> throw new UnsupportedOperationException("stream of " + member.memberType());
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
      in.readCharSkipWhitespace('"');
      String name = in.readString();
      in.readCharSkipWhitespace(':');
      Member member = frame.getMember(name);
      if (member == null) {
        // input has a member that is not mapped to java, we ignore it
        cp = in.skipNodeDetect();
        frame.suspendedAtMember = null;
        continue;
      } else if (member.memberType.isSuspending()) {
        frame.checkNotAlreadyProcessed(member);
        frame.suspendedAtMember = member.name;
        frame.isSuspended = true;
        return;
      }
      frame.suspendedAtMember = null;
      // MAPPED_VALUE
      cp = in.readNodeDetect(val -> frame.setValue(member, val));
    }
    frame.isClosed = true;
    // need to end frame of single proxy objects (unless it is the root)
    if (frame.of.memberType() == PROXY_OBJECT && stack.size() > 1) popFrame(frame.of);
  }

  private Object toJavaType(Member member, Object value, Object[] args) {
    if (value == null) return member.providesDefault()
        ? args[member.getDefaultValueIndex()]
        : member.nullValue();
    if (value instanceof List<?> list) return toJavaCollection(member, list);
    if (value instanceof Map<?,?> map) return toJavaMap(member, map);
    //TODO the jsonTo could be pre-computed for each member but members are global and mapping is local
    // therefore this can only be added to the frame holding the converter for reach member
    // this needs to be dynamic as the input can change, e.g. string, number
    // to implement this the toJavaType needs to be split in two steps
    // 1. create the UnaryOperator that does the to-java conversion for the target type given a certain source class
    // 2. apply the computed operator to the current value (given it has same source class, otherwise update operator)
    return toSimpleJavaType(member.valueType(), value);
  }

  private Object toSimpleJavaType(Class<?> to, Object value) {
    if (to == Serializable.class) return value; // => keep as is
    if (value instanceof String s) return to == String.class ? s : jsonTo(to).mapString().apply(s);
    if (value instanceof Boolean b)
      return to == boolean.class || to == Boolean.class ? b : jsonTo(to).mapBoolean().apply(b);
    if (value instanceof Number n) return toJavaNumberType(to, n);
    throw new UnsupportedOperationException("JSON value not supported: " + value);
  }

  /** A JSON number is mapped to a java {@link Number} subtype */
  private Object toJavaNumberType(Class<?> to, Number n) {
    if (to == Number.class) return n;
    if (to == int.class || to == Integer.class) return n.intValue();
    if (to == long.class || to == Long.class) return n.longValue();
    if (to == float.class || to == Float.class) return n.floatValue();
    if (to == double.class || to == Double.class) return n.doubleValue();
    if (to.isInstance(n)) return n;
    return jsonTo(to).mapNumber().apply(n);
  }

  /**
   * A JSON array is mapped to a java collection subtype.
   * The elements are always "simple" mapped values.
   */
  private Collection<?> toJavaCollection(Member member, List<?> list) {
    Class<?> elementType = member.valueType();
    Class<?> collectionType = member.collectionType();
    if (collectionType == List.class || collectionType == Collection.class)
      return list.stream().map(v -> toSimpleJavaType(elementType, v)).toList();
    if (collectionType == Set.class)
      return list.stream().map(v -> toSimpleJavaType(elementType, v)).collect(toUnmodifiableSet());
    throw new UnsupportedOperationException("Collection type not supported: "+collectionType);
  }

  private Map<?,?> toJavaMap(Member member, Map<?,?> map) {
    return map.entrySet().stream().collect(toUnmodifiableMap(
        e -> toSimpleJavaType(member.keyType(), e.getKey()),
        e -> toSimpleJavaType(member.valueType(), e.getValue())));
  }

  private Void objectAsProxyConsumer(Member member, Object[] args) {
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

  private Void arrayViaProxyConsumer(Member member, Object[] args) {
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

  private Void arrayViaMappedConsumer(Member member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Object> consumer = (Consumer<Object>) args[0];
    arrayAsMappedIterator(member, args).forEachRemaining(consumer);
    return null; // = void
  }

  private Stream<?> arrayAsMappedStream(Member member, Object[] args) {
    return toStream(arrayAsMappedIterator(member, args));
  }

  private Iterator<?> arrayAsMappedIterator(Member member, Object[] args) {
    JsonFrame frame = currentFrame();
    frame.mappedStreamItemNo = -1;
    return new Iterator<>() {
      final Class<?> keyType = member.keyType();
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
        cp = in.readNodeDetect(val -> frame.setValue(member, toJavaType(member, val, args)));
        return keyType == null
            ? frame.getValue(member)
            : new SimpleEntry<>(toSimpleJavaType(keyType, frame.mappedStreamItemNo), frame.getValue(member));
      }
    };
  }

  private Void objectAsMappedEntryConsumer(Member member, Object[] args) {
    @SuppressWarnings("unchecked")
    Consumer<Entry<?, ?>> consumer = (Consumer<Entry<?, ?>>) args[0];
    objectAsMappedEntryIterator(member, args).forEachRemaining(consumer);
    return null; // = void
  }

  private Stream<Entry<?,?>> objectAsMappedEntryStream(Member member, Object[] args) {
    return toStream(objectAsMappedEntryIterator(member, args));
  }

  private Iterator<Entry<?,?>> objectAsMappedEntryIterator(Member member, Object[] args) {
    JsonFrame frame = currentFrame();
    return new Iterator<>() {
      final Class<?> keyType = member.keyType();
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
        if (cp == '}') throw new NoSuchElementException(""+frame.mappedStreamItemNo);
        // opening " already consumed in hasNext
        String key = in.readString();
        in.readCharSkipWhitespace(':');
        // reading value and either , or }
        cp = in.readNodeDetect(val -> frame.setValue(member, toJavaType(member, val, args)));
        return new SimpleEntry<>(toSimpleJavaType(keyType, key), frame.getValue(member));
      }
    };
  }

  private static <T> Stream<T> toStream(Iterator<T> iter) {
    return StreamSupport.stream(spliteratorUnknownSize(iter, STREAM_CHARACTERISTICS), false);
  }

  private JsonFrame currentFrame() {
    return stack.peekFirst();
  }

  private void popFrame(Member member) {
    JsonFrame done = stack.removeFirst();
    JsonFrame frame = currentFrame();
    if (frame != null) frame.markAsProcessed(member, done.proxyStreamItemNo + 1);
  }

  private JsonFrame pushFrame(Member member) {
    Class<?> type = member.valueType();
    JsonFrame frame = new JsonFrame(member, newProxy(type, this), initStreamType(type));
    stack.addFirst(frame);
    return frame;
  }

  private static Object newProxy(Class<?> type, JsonStream handler) {
    return Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, handler);
  }

  /** Holds the information and parse state for JSON input for a single JSON object level. */
  private static class JsonFrame implements Iterable<Entry<String, Object>> {
    final Member of;
    final Object proxy;

    private final Map<String, Member> members;
    /**
     * The values of members read so far, for streaming members this holds the number of streamed
     * items once the member is done.
     *
     * <p>Index 0 is always for the key member, if no such member exist first value is at index 1
     */
    private final Object[] values;
    private final Object[] nullValues;
    /**
     * For each value (same index as {@link #values}) this remembers the order the value appeared in
     * the input
     */
    private final int[] memberInputOrder;
    private final int[] zeroMemberInputOrder;

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
     * A {@link MemberType#isStreaming()} input that is not yet been processed - if set we are at the
     * start of that member value in the input stream
     */
    String suspendedAtMember;

    int proxyStreamItemNo = -1;

    int mappedStreamItemNo = -1;

    private JsonFrame(Member of, Object proxy, Map<String, Member> members) {
      this.of = of;
      this.proxy = proxy;
      this.members = members;
      int size = members.size() + 1;
      this.values = new Object[size];
      this.nullValues = new Object[size];
      this.memberInputOrder = new int[size];
      this.zeroMemberInputOrder = new int[size];
    }

    /** @return the current values of this frame by member name (only used in {@link #toString()} */
    @Override
    public Iterator<Entry<String, Object>> iterator() {
      @SuppressWarnings("unchecked")
      Entry<String, Object>[] entries = new Entry[memberInputOrder.length];
      for (Member m : members.values()) {
        int i = m.index();
        if (values[i] != null) entries[memberInputOrder[i]] = new SimpleEntry<>(m.name, values[i]);
      }
      return Arrays.stream(entries).filter(Objects::nonNull).toList().iterator();
    }

    Member getMember(String name) { return members.get(name); }

    void setValue(Member member, Object value) {
      int index = member == null ? 0 : member.index;
      values[index] = value;
      memberInputOrder[index] = memberInputCount++;
    }

    Object getValue(Member member) {
      return values[member.index];
    }

    void markAsProcessed(Member member, int itemNo) {
      setValue(member, itemNo);
    }

    void nextProxyInStream() {
      proxyStreamItemNo++;
      if (proxyStreamItemNo >= of.maxOccur) throw maxOccurExceeded(of.valueType, of.maxOccur);
      memberInputCount = 0;
      mappedStreamItemNo = -1;
      isOpened = false;
      isClosed = false;
      isSuspended = false;
      suspendedAtMember = null;
      arraycopy(nullValues, 0, values, 0, values.length);
      arraycopy(zeroMemberInputOrder, 0, memberInputOrder, 0, memberInputOrder.length);
    }

    void nextMappedValueInStream() {
      mappedStreamItemNo++;
      if (proxyStreamItemNo >= of.maxOccur) throw maxOccurExceeded(of.valueType, of.maxOccur);
    }

    void checkNotAlreadyProcessed(Member member) {
      if (values[member.index] != null)
        throw JsonSchemaException.outOfOrder(
            member.name,
            members.values().stream()
                .filter(m -> m.memberType.isStreaming())
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
    PROXY_CONSUMER;

    boolean isStreaming() {
      return this != MAPPED_VALUE && this != PROXY_OBJECT;
    }

    boolean isSuspending() {
      return this != MAPPED_VALUE;
    }

    boolean isConsumer() {
      return this == PROXY_CONSUMER || this == MAPPED_CONSUMER;
    }
  }

  /**
   * Holds the "meta" information on a member {@link Method} of a {@link Stream} interface
   * returnType.
   *
   * @param returnType raw method return type
   * @param valueType  java type JSON values should be converted to
   * @param providesDefault true in case the method represented by this member has a default
   *     argument to return in case the member is not present or given as JSON null
   */
  private record Member(
      MemberType memberType,
      String name,
      int index,
      Class<?> returnType,
      Class<?> collectionType,
      Class<?> keyType,
      Class<?> valueType,
      Object nullValue,
      boolean providesDefault,
      boolean isKeyProperty,
      int minOccur,
      int maxOccur) {

    public static Member newMember(Method m, int index) {
      MemberType memberType = memberType(m);
      boolean keyProperty = isKeyProperty(m);
      return new Member(
          memberType,
          name(m),
          keyProperty ? 0 : index,
          m.getReturnType(),
          collectionType(m, memberType),
          keyType(m, memberType),
          valueType(m, memberType),
          nullValue(m, memberType),
          providesDefault(m, memberType),
          keyProperty,
          minOccur(m),
          maxOccur(m));
    }

    int getDefaultValueIndex() {
      return memberType.isConsumer() ? 1 : 0;
    }

    private static MemberType memberType(Method m) {
      Class<?> as = m.getReturnType();
      if (as == Stream.class)
        return isProxyInterface(actualGenericRawType(m.getGenericReturnType(), 0))
            ? PROXY_STREAM
            : MAPPED_STREAM;
      if (as == Iterator.class)
        return isProxyInterface(actualGenericRawType(m.getGenericReturnType(), 0))
            ? PROXY_ITERATOR
            : MAPPED_ITERATOR;
      if (as == void.class
          && m.getParameterCount() == 1
          && m.getParameterTypes()[0] == Consumer.class)
        return isProxyInterface(actualGenericRawType(m.getGenericParameterTypes()[0], 0))
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

    private static Class<?> collectionType(Method m, MemberType memberType) {
      Type cType = memberType.isConsumer() ? m.getGenericParameterTypes()[0] : m.getGenericReturnType();
      if (memberType.isStreaming())
        cType = actualGenericType(cType, 0);
      Class<?> cClass = toRawType(cType);
      if (Collection.class.isAssignableFrom(cClass) || Map.class == cClass)
        return cClass;
      return null;
    }

    private static Class<?> valueType(Method m, MemberType memberType) {
      return switch (memberType) {
        case PROXY_OBJECT -> m.getReturnType();
        case MAPPED_VALUE -> valueTypeSimple(m.getGenericReturnType());
        case PROXY_CONSUMER -> actualGenericRawType(m.getGenericParameterTypes()[0], 0);
        case MAPPED_CONSUMER -> valueTypeInStream(m.getGenericParameterTypes()[0]);
        case PROXY_STREAM, PROXY_ITERATOR -> actualGenericRawType(m.getGenericReturnType(), 0);
        case MAPPED_STREAM, MAPPED_ITERATOR -> valueTypeInStream(m.getGenericReturnType());
      };
    }

    private static Class<?> valueTypeSimple(Type memberType) {
      Class<?> rawType = toRawType(memberType);
      if (rawType == Map.class)
        return actualGenericRawType(memberType, 1);
      if (Collection.class.isAssignableFrom(rawType))
        return actualGenericRawType(memberType, 0);
      return rawType;
    }

    private static Class<?> valueTypeInStream(Type streamType) {
      Type itemType = actualGenericType(streamType, 0);
      if (toRawType(itemType) == Entry.class)
        return actualGenericRawType(itemType, 1);
      return valueTypeSimple(itemType);
    }

    private static Class<?> keyType(Method m, MemberType memberType) {
      return switch (memberType) {
        case MAPPED_CONSUMER -> keyTypeInStream(m.getGenericParameterTypes()[0]);
        case MAPPED_ITERATOR, MAPPED_STREAM -> keyTypeInStream(m.getGenericReturnType());
        case MAPPED_VALUE -> keyTypeInMapped(m.getGenericReturnType());
        default -> null;
      };
    }

    private static Class<?> keyTypeInMapped(Type memberType) {
      Class<?> rawType = toRawType(memberType);
      if (rawType == Map.class)
        return actualGenericRawType(memberType, 0);
      return null;
    }

    private static Class<?> keyTypeInStream(Type streamType) {
      Type itemType = actualGenericType(streamType, 0);
      if (toRawType(itemType) == Entry.class)
        return actualGenericRawType(itemType, 0);
      return null;
    }

    private static boolean providesDefault(Method m, MemberType memberType) {
      int defaultValueIndex = memberType.isConsumer() ? 1 : 0;
      return m.getParameterCount() == defaultValueIndex+1
          && m.getGenericReturnType().equals(m.getGenericParameterTypes()[defaultValueIndex]);
    }

    private static boolean isKeyProperty(Method m) {
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

    private static Object nullValue(Method m, MemberType memberType) {
      Class<?> as = m.getReturnType();
      if (!as.isPrimitive()) {
        // TODO empty collections?
        return switch (memberType) {
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

    private static Class<?> actualGenericRawType(Type type, int index) {
      return toRawType(actualGenericType( type, index));
    }

    private static Type actualGenericType(Type type, int index) {
      return ((ParameterizedType)type).getActualTypeArguments()[index];
    }

    private static Class<?> toRawType(Type type) {
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
