package se.jbee.json.stream;

import static java.lang.Character.isUpperCase;
import static java.lang.Character.toLowerCase;
import static se.jbee.json.stream.JavaMember.ProcessingType.MAPPED_CONSUMER;
import static se.jbee.json.stream.JavaMember.ProcessingType.MAPPED_ITERATOR;
import static se.jbee.json.stream.JavaMember.ProcessingType.MAPPED_STREAM;
import static se.jbee.json.stream.JavaMember.ProcessingType.MAPPED_VALUE;
import static se.jbee.json.stream.JavaMember.ProcessingType.PROXY_CONSUMER;
import static se.jbee.json.stream.JavaMember.ProcessingType.PROXY_ITERATOR;
import static se.jbee.json.stream.JavaMember.ProcessingType.PROXY_OBJECT;
import static se.jbee.json.stream.JavaMember.ProcessingType.PROXY_STREAM;
import static se.jbee.json.stream.JavaMember.ProcessingType.RAW_VALUES;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Holds the "meta" information on a member {@link Method} which represents the Java side (target)
 * of a JSON object member (source).
 *
 * @param processingType how the JSON input is processed/mapped to Java
 * @param jsonName how the member is called in the JSON input
 * @param index index of this member within a frame starting with 1 as lowest index. All methods
 *     targeting the same JSON input member share the same index
 * @param returnType raw method return type
 * @param collectionType Java type of a Java collection if it is the stream element or return type
 *     or null if the method has no collection type
 * @param valueType Java type simple JSON values in a stream or collection are converted to
 * @param keyType Java type used when a JSON object member jsonName is mapped to a Java {@link Map}
 *     key type or null if the method has no map collection type
 * @param hasDefaultParameter true in case the method represented by this member has a default
 *     argument to return in case the member is not present or given as JSON null
 * @param isKeyProperty true, if the member returns the name of JSON object members (map key when JSON objects used as map)
 * @param retainNull when true, JSON null or undefined (no such member) translates to Java {@code null} independent of any mapping settings
 * @param jsonDefaultValue when non-null this is the Java equivalent of the JSON value provided via annotation that should be used in case the member is null or undefined in the JSON input. The JSON equivalent Java is mapped to target type as usual.
 */
record JavaMember(
    ProcessingType processingType,
    String jsonName,
    int index,
    Class<?> returnType,
    Class<?> collectionType,
    Class<?> keyType,
    Class<?> valueType,
    boolean hasDefaultParameter,
    boolean isKeyProperty,
    boolean retainNull,
    Object jsonDefaultValue,
    int minOccur,
    int maxOccur) {

  /**
   * Based on the type information extracted from user defined target interfaces each object member
   * is processed in one of the ways described below.
   *
   * <p>This is purely to only have to analyse the type of processing needed once and then remember
   * it. This could have otherwise been derived from a proxied {@link Method} each time it is
   * called.
   */
  enum ProcessingType {
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
    PROXY_CONSUMER,
    /**
     * All simple JSON members are mapped to a {@link Map}
     * where key is the member name {@link String}, and value is the Java equivalent type of the JSON value.
     * Their common type is {@link java.io.Serializable}.
     *
     * These values are not mapped as there is no single Java target type.
     *
     * As this is not stream processed the operation is repeatable.
     */
    RAW_VALUES;
    // also allow a second parameter for the JsonToJava mapping?

    boolean isStreaming() {
      return isSuspending() && this != PROXY_OBJECT;
    }

    boolean isSuspending() {
      return this != MAPPED_VALUE && this != RAW_VALUES;
    }

    boolean isConsumer() {
      return this == PROXY_CONSUMER || this == MAPPED_CONSUMER;
    }
  }

  /**
   * As all classes whose methods we put in here are interfaces where is no risk of any memory
   * leaks. This can just be shared in the JVM.
   */
  private static final Map<Method, JavaMember> MEMBERS_BY_METHOD = new ConcurrentHashMap<>();

  private static final Map<Class<?>, Map<String, JavaMember>> MEMBERS_BY_TYPE =
      new ConcurrentHashMap<>();

  public static <T> Map<String, JavaMember> getMembersOf(Class<T> type) {
    if (!type.isInterface())
      throw new IllegalArgumentException(
          "Stream must be mapped to an interface returnType but got: " + type);
    return MEMBERS_BY_TYPE.computeIfAbsent(
        type,
        key -> {
          Map<String, JavaMember> membersByJsonName = new HashMap<>();
          int n = 1;
          for (Method m : key.getMethods()) {
            if (isJsonMappedMember(m)) {
              String jsonName = JavaMember.computeJsonName(m);
              int index =
                  membersByJsonName.containsKey(jsonName)
                      ? membersByJsonName.get(jsonName).index()
                      : n++;
              JavaMember member = JavaMember.newMember(m, index);
              MEMBERS_BY_METHOD.put(m, member);
              if (!membersByJsonName.containsKey(jsonName) || m.getParameterCount() == 0) {
                membersByJsonName.put(jsonName, member);
              }
            }
          }
          return Map.copyOf(membersByJsonName);
        });
  }

  private static boolean isJsonMappedMember(Method m) {
    int pc = m.getParameterCount();
    Class<?> rt = m.getReturnType();
    return !m.isDefault()
        && !m.isSynthetic()
        && !Modifier.isStatic(m.getModifiers())
        && !m.getName().equals("skip")
        && (pc == 0 && rt != void.class
          || pc == 1 && rt != void.class && m.getGenericParameterTypes()[0].equals(m.getGenericReturnType())
          || pc == 1 && rt == void.class && m.getParameterTypes()[0] == Consumer.class);
  }

  public static JavaMember newRootMember(
      ProcessingType processingType, Class<?> returnType, Class<?> valueType) {
    return new JavaMember(
        processingType,
        "",
        1,
        returnType,
        null,
        null,
        valueType,
        false,
        false,
        false,
        "",
        0,
        Integer.MAX_VALUE);
  }

  public static JavaMember newMember(Method m, int index) {
    ProcessingType processingType = detectProcessingType(m);
    Class<?> collectionType = computeCollectionType(m, processingType);
    boolean isKeyProperty = computeIsKeyProperty(m);
    Class<?> valueType = computeValueType(m, processingType);
    JsonProperty property = m.getAnnotation(JsonProperty.class);
    Object jsonDefaultValue = null;
    if (property != null && !property.defaultValue().isEmpty()) {
      jsonDefaultValue = JsonReader.parse(property.defaultValue());
    }
    return new JavaMember(
        processingType,
        computeJsonName(m),
        isKeyProperty ? 0 : index,
        m.getReturnType(),
        collectionType,
        computeKeyType(m, processingType),
        valueType,
        computeHasDefaultParameter(m, processingType),
        isKeyProperty,
        processingType == RAW_VALUES || property != null && property.retainNull(),
        jsonDefaultValue,
        computeMinOccur(m),
        computeMaxOccur(m));
  }

  public static JavaMember getMember(Method method) {
    return MEMBERS_BY_METHOD.get(method);
  }

  public int getDefaultValueParameterIndex() {
    return processingType.isConsumer() ? 1 : 0;
  }

  public Class<?> nullValueType() {
    return collectionType != null ? collectionType : valueType;
  }

  public JsonToJava.JsonTo<?> jsonToValueType(JsonToJava toJava) {
    return valueType == null ? null : toJava.mapTo(valueType);
  }

  public JsonToJava.JsonTo<?> jsonToKeyType(JsonToJava toJava) {
    return keyType == null ? null : toJava.mapTo(keyType);
  }

  private static ProcessingType detectProcessingType(Method m) {
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
    if (as == Map.class) {
      Type entry = m.getGenericReturnType();
      if (actualGenericRawType(entry, 0) == String.class
          && actualGenericRawType(entry, 1) == Serializable.class)
        return RAW_VALUES;
      return MAPPED_VALUE;
    }
    return isProxyInterface(as) ? PROXY_OBJECT : MAPPED_VALUE;
  }

  private static String computeJsonName(Method m) {
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

  private static Class<?> computeCollectionType(Method m, ProcessingType processingType) {
    java.lang.reflect.Type cType =
        processingType.isConsumer() ? m.getGenericParameterTypes()[0] : m.getGenericReturnType();
    if (processingType.isStreaming()) cType = actualGenericType(cType, 0);
    Class<?> cClass = toRawType(cType);
    if (Collection.class.isAssignableFrom(cClass) || Map.class == cClass) return cClass;
    return null;
  }

  private static Class<?> computeValueType(Method m, ProcessingType processingType) {
    return switch (processingType) {
      case RAW_VALUES -> Serializable.class;
      case PROXY_OBJECT -> m.getReturnType();
      case MAPPED_VALUE -> valueTypeSimple(m.getGenericReturnType());
      case PROXY_CONSUMER -> actualGenericRawType(m.getGenericParameterTypes()[0], 0);
      case MAPPED_CONSUMER -> valueTypeInStream(m.getGenericParameterTypes()[0]);
      case PROXY_STREAM, PROXY_ITERATOR -> actualGenericRawType(m.getGenericReturnType(), 0);
      case MAPPED_STREAM, MAPPED_ITERATOR -> valueTypeInStream(m.getGenericReturnType());
    };
  }

  private static Class<?> valueTypeSimple(java.lang.reflect.Type type) {
    Class<?> rawType = toRawType(type);
    if (rawType == Map.class) return actualGenericRawType(type, 1);
    if (Collection.class.isAssignableFrom(rawType)) return actualGenericRawType(type, 0);
    return rawType;
  }

  private static Class<?> valueTypeInStream(java.lang.reflect.Type streamType) {
    java.lang.reflect.Type itemType = actualGenericType(streamType, 0);
    if (toRawType(itemType) == Map.Entry.class) return actualGenericRawType(itemType, 1);
    return valueTypeSimple(itemType);
  }

  private static Class<?> computeKeyType(Method m, ProcessingType processingType) {
    return switch (processingType) {
      case MAPPED_CONSUMER -> keyTypeInStream(m.getGenericParameterTypes()[0]);
      case MAPPED_ITERATOR, MAPPED_STREAM -> keyTypeInStream(m.getGenericReturnType());
      case MAPPED_VALUE -> keyTypeInMapped(m.getGenericReturnType());
      default -> null;
    };
  }

  private static Class<?> keyTypeInMapped(java.lang.reflect.Type type) {
    Class<?> rawType = toRawType(type);
    if (rawType == Map.class) return actualGenericRawType(type, 0);
    return null;
  }

  private static Class<?> keyTypeInStream(java.lang.reflect.Type streamType) {
    java.lang.reflect.Type itemType = actualGenericType(streamType, 0);
    if (toRawType(itemType) == Map.Entry.class) return actualGenericRawType(itemType, 0);
    return null;
  }

  private static boolean computeHasDefaultParameter(Method m, ProcessingType processingType) {
    int defaultValueIndex = processingType.isConsumer() ? 1 : 0;
    return m.getParameterCount() == defaultValueIndex + 1
        && m.getGenericReturnType().equals(m.getGenericParameterTypes()[defaultValueIndex]);
  }

  private static boolean computeIsKeyProperty(Method m) {
    return m.isAnnotationPresent(JsonProperty.class) && m.getAnnotation(JsonProperty.class).key()
        || m.getName().equals("key");
  }

  private static int computeMinOccur(Method m) {
    JsonProperty member = m.getAnnotation(JsonProperty.class);
    return member == null ? 0 : Math.max(member.minOccur(), member.required() ? 1 : 0);
  }

  private static int computeMaxOccur(Method m) {
    JsonProperty member = m.getAnnotation(JsonProperty.class);
    return member == null ? Integer.MAX_VALUE : Math.max(member.maxOccur(), computeMinOccur(m));
  }

  private static Class<?> actualGenericRawType(java.lang.reflect.Type type, int index) {
    return toRawType(actualGenericType(type, index));
  }

  private static java.lang.reflect.Type actualGenericType(java.lang.reflect.Type type, int index) {
    return ((ParameterizedType) type).getActualTypeArguments()[index];
  }

  private static Class<?> toRawType(java.lang.reflect.Type type) {
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
        && !Map.Entry.class.isAssignableFrom(t);
  }
}
