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
   * {@code retainNull} takes precedence over {@link JsonToJava.JsonTo#mapNull()} but is ignored when {@link #defaultValue()} is specified.
   *
   * @return  If true, this member becomes Java {@code null} when undefined or defined JSON null in the input
   */
  boolean retainNull() default false;

  /**
   * The JSON input to assume when no input is provided for this member making it its effective default value.
   *
   * {@code defaultValue} takes precedence over {@link #retainNull()} and {@link JsonToJava.JsonTo#mapNull()}.
   *
   * @return when specified the provided JSON is assumed when this member is undefined or defined JSON null in the input.
   */
  String defaultValue() default "";

  /*
  Validation
   */

  boolean required() default false;

  int minOccur() default 0;

  int maxOccur() default Integer.MAX_VALUE;

  // int maxLength(); // for strings (number of chars) and numbers (number of digits)

  // int maxDepth() default 1; // also analysis of type Map<?, Map<?,?> would be 2

  // int maxSize(); // for collection level

  enum JsonType { STRING, NUMBER, BOOLEAN, ARRAY, OBJECT }

  /**
   * An empty set is same as accepting all the types.
   *
   * @return the set of JSON value types that is accepted as valid input and as such tried to be mapped to the Java target type
   */
  JsonType[] accept() default {};
}
