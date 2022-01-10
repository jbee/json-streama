package se.jbee.json.stream;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonMember {

	/*
	Mapping
	 */

	/**
	 * The key used internally when an JSON input object is treated as if it was a sequence of objects which had a
	 * special member called "(key)".
	 * <p>
	 * This means to access this using a java method annotate the method with {@link JsonMember} with either the {@link
	 * #name()} set to this constant or with {@link #key()} set true.
	 */
	String OBJECT_KEY = "(key)";

	String name() default "";

	boolean key() default false;

	boolean value() default false;

	/*
	Validation
	 */

	boolean required() default false;

	int minOccur() default 0;

	int maxOccur() default 1;
}
