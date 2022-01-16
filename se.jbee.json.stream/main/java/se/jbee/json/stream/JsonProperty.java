package se.jbee.json.stream;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonProperty {

  /*
  Mapping
   */

  /**
   * The key used internally when an JSON input "map" object is treated as if it was a sequence of
   * objects which had a special member called "(key)". This is accessed by annotating with {@link
   * #key()} of {@code true}.
   */
  String OBJECT_KEY = "(key)";

  String name() default "";

  boolean key() default false;

  /*
  Validation
   */

  boolean required() default false;

  int minOccur() default 0;

  int maxOccur() default Integer.MAX_VALUE;
}
