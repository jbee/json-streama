package se.jbee.json.stream;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// TODO also allow on type level, there name becomes a prefix, key is ignored
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonProperty {

  /*
  Mapping
   */

  String name() default "";

  boolean key() default false;

  /*
   * Nullness
   */

  /**
   * By default, when the annotated member is either undefined or defined JSON null in the input
   * {@link JsonToJava.JsonTo#mapNull()} for the target type is used.
   *
   * <p>{@code retainNulls} takes precedence over {@link JsonToJava.JsonTo#mapNull()} but is ignored
   * when {@link #defaultValue()} is specified.
   *
   * @return If true, this member becomes Java {@code null} when undefined or defined JSON null in
   *     the input
   */
  boolean retainNulls() default false;

  /**
   * The JSON input to assume when no input is provided for this member making it its effective
   * default value.
   *
   * <p>{@code defaultValue} takes precedence over {@link #retainNulls()} and {@link
   * JsonToJava.JsonTo#mapNull()}.
   *
   * @return when specified the provided JSON is assumed when this member is undefined or defined
   *     JSON null in the input.
   */
  String defaultValue() default "";

  /*
  Constraints
   */

  boolean required() default false;

  int minOccur() default 0;

  int maxOccur() default Integer.MAX_VALUE;

  /** @return maximum number of chars in a string or maximum number of digits in a number */
  int maxLength() default -1;

  /**
   * Examples:
   *
   * <ol>
   *   <li>-1 : automatically determined based on the annotated return type
   *   <li>0 : no nesting (simple values)
   *   <li>1 : 1 level of nesting, for example an array or object with simple values
   *   <li>2 : 2 levels of nesting, for example for a {@code List<List<String>>}
   * </ol>
   *
   * @return number of nesting levels a value may have.
   */
  int maxDepth() default -1; // also analysis of type Map<?, Map<?,?> would be 2

  /**
   * This is the same as {@link #maxOccur()} in case the annotated return type is not a stream
   * processed type but a directly mapped collection.
   *
   * @return maximum number elements in a collection
   */
  int maxSize() default -1;

  enum JsonType {
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT
  }

  /**
   * An empty set means the set is determined based on the Java target type of the annotated method.
   * For most part this is equivalent to accepting all types but for simple types like {@link
   * String}, {@link Number}s or {@link Boolean} complex types {@link JsonType#ARRAY} and {@link
   * JsonType#OBJECT} are excluded.
   *
   * @return the set of JSON value types that is accepted as valid input and as such tried to be
   *     mapped to the Java target type
   */
  JsonType[] accept() default {};
}
