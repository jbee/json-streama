package se.jbee.json.stream;

import static java.lang.String.format;

/** Thrown in case one of the constraints provided via {@link JsonProperty} is violated. */
public class JsonConstraintException extends JsonProcessingException {

  public static JsonConstraintException minOccurNotReached(Class<?> type, int minOccur, int occur) {
    return new JsonConstraintException(
        format(
            "Minimum expected number of %s items is %d but only found %d.",
            type.getSimpleName(), minOccur, occur));
  }

  public static JsonConstraintException maxOccurExceeded(Class<?> type, int maxOccur) {
    return new JsonConstraintException(
        format("Maximum expected number of %s items is %d.", type.getSimpleName(), maxOccur));
  }

  protected JsonConstraintException(String s) {
    super(s);
  }
}
