package se.jbee.json.stream;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonMember {

	String name() default "";

	boolean key() default false;

	boolean value() default false;

	boolean required() default false;

	int minOccur() default 0;

	int maxOccur() default 1;
}
