package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static se.jbee.json.stream.JsonStream.ofRoot;
import static test.integration.Utils.asJsonInput;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonProperty;
import se.jbee.json.stream.JsonToJava;

/**
 * Tests the {@code null} related aspects and features of the {@link JsonToJava#DEFAULT} as well as
 * the related features of {@link JsonProperty} to adjust them.
 */
class TestJsonStreamNulls {

  interface PrimitiveRoot {
    int intValue();

    long longValue();

    double doubleValue();

    float floatValue();

    boolean booleanValue();

    char charValue();
  }

  interface WrapperRoot {
    Integer integerValue();

    Long longValue();

    Double doubleValue();

    Float floatValue();

    Boolean booleanValue();

    Character characterValue();
  }

  interface CollectionRoot {
    List<String> list();

    Set<String> set();

    Collection<String> collection();

    Map<String, String> map();
  }

  /**
   * Undefined or JSON null in the input translates to Java {@code null} because of {@link
   * JsonProperty#retainNull()} which has higher precedence than any mapping
   */
  interface CollectionNullRoot {
    @JsonProperty(retainNull = true)
    List<String> list();

    @JsonProperty(retainNull = true)
    Set<String> set();

    @JsonProperty(retainNull = true)
    Collection<String> collection();

    @JsonProperty(retainNull = true)
    Map<String, String> map();
  }

  /**
   * Undefined or JSON null in the input translates to the annotated {@link
   * JsonProperty#defaultValue()}
   */
  interface CollectionDefaultValueRoot {
    @JsonProperty(defaultValue = "[]")
    List<String> list();

    @JsonProperty(defaultValue = "[1, 2,\t  3]")
    Set<Integer> set();

    @JsonProperty(defaultValue = """
["a","b","c"]
""")
    Collection<String> collection();

    @JsonProperty(defaultValue = "{\"a\":\"b\"}")
    Map<String, String> map();
  }

  @Test
  void null_PrimitivesHaveNullValueWhenUndefined() {
    String json = // language=JSON
        """
        {}
        """;
    PrimitiveRoot root = ofRoot(PrimitiveRoot.class, asJsonInput(json));
    assertEquals(0, root.intValue());
    assertEquals(0L, root.longValue());
    assertEquals(0d, root.doubleValue());
    assertEquals(0f, root.floatValue());
    assertFalse(root.booleanValue());
    assertEquals((char) 0, root.charValue());
  }

  @Test
  void null_PrimitivesHaveNullValueWhenDefinedNull() {
    String json = // language=JSON
        """
        {
        "intValue": null,
        "longValue": null,
        "doubleValue":null,
        "floatValue": null,
        "booleanValue": null,
        "charValue": null
        }
        """;
    PrimitiveRoot root = ofRoot(PrimitiveRoot.class, asJsonInput(json));
    assertEquals(0, root.intValue());
    assertEquals(0L, root.longValue());
    assertEquals(0d, root.doubleValue());
    assertEquals(0f, root.floatValue());
    assertFalse(root.booleanValue());
    assertEquals((char) 0, root.charValue());
  }

  @Test
  void null_WrapperAreNullWhenUndefined() {
    String json = // language=JSON
        """
        {}
        """;
    WrapperRoot root = ofRoot(WrapperRoot.class, asJsonInput(json));
    assertNull(root.integerValue());
    assertNull(root.longValue());
    assertNull(root.doubleValue());
    assertNull(root.floatValue());
    assertNull(root.booleanValue());
    assertNull(root.characterValue());
  }

  @Test
  void null_WrapperAreNullWhenDefinedNull() {
    String json = // language=JSON
        """
        {
        "integerValue": null,
        "longValue": null,
        "doubleValue":null,
        "floatValue": null,
        "booleanValue": null,
        "characterValue": null
        }
        """;
    WrapperRoot root = ofRoot(WrapperRoot.class, asJsonInput(json));
    assertNull(root.integerValue());
    assertNull(root.longValue());
    assertNull(root.doubleValue());
    assertNull(root.floatValue());
    assertNull(root.booleanValue());
    assertNull(root.characterValue());
  }

  @Test
  void null_CollectionsAreEmptyWhenUndefined() {
    String json = // language=JSON
        """
        {}
        """;
    CollectionRoot root = ofRoot(CollectionRoot.class, asJsonInput(json));
    assertEquals(List.of(), root.list());
    assertEquals(Set.of(), root.set());
    assertEquals(List.of(), root.collection());
    assertEquals(Map.of(), root.map());
  }

  @Test
  void null_CollectionsAreEmptyWhenDefinedNull() {
    String json = // language=JSON
        """
        {
        "list": null,
        "set": null,
        "collection": null,
        "map": null
        }
        """;
    CollectionRoot root = ofRoot(CollectionRoot.class, asJsonInput(json));
    assertEquals(List.of(), root.list());
    assertEquals(Set.of(), root.set());
    assertEquals(List.of(), root.collection());
    assertEquals(Map.of(), root.map());
  }

  @Test
  void null_CollectionsMapNullWhenUndefined() {
    JsonToJava toJava =
        JsonToJava.DEFAULT
            .with(List.class, null, List::of)
            .with(Set.class, null, Set::of)
            .with(Collection.class, null, List::of)
            .with(Map.class, null, e -> Map.of("value", e));

    String json = // language=JSON
        """
        {}
        """;
    CollectionRoot root = ofRoot(CollectionRoot.class, asJsonInput(json), toJava);
    assertNull(root.list());
    assertNull(root.set());
    assertNull(root.collection());
    assertNull(root.map());
  }

  @Test
  void null_CollectionsMapNullWhenDefinedNull() {
    JsonToJava toJava =
        JsonToJava.DEFAULT
            .with(List.class, null, List::of)
            .with(Set.class, null, Set::of)
            .with(Collection.class, null, List::of)
            .with(Map.class, null, e -> Map.of("value", e));

    String json = // language=JSON
        """
        {
        "list": null,
        "set": null,
        "collection": null,
        "map": null
        }
        """;
    CollectionRoot root = ofRoot(CollectionRoot.class, asJsonInput(json), toJava);
    assertNull(root.list());
    assertNull(root.set());
    assertNull(root.collection());
    assertNull(root.map());
  }

  @Test
  void null_CollectionsRetainNullWhenUndefined() {
    String json = // language=JSON
        """
        {}
        """;
    CollectionNullRoot root = ofRoot(CollectionNullRoot.class, asJsonInput(json));
    assertNull(root.list());
    assertNull(root.set());
    assertNull(root.collection());
    assertNull(root.map());
  }

  @Test
  void null_CollectionsRetainNullWhenDefinedNull() {
    String json = // language=JSON
        """
            {
            "list": null,
            "set": null,
            "collection": null,
            "map": null
            }
            """;
    CollectionNullRoot root = ofRoot(CollectionNullRoot.class, asJsonInput(json));
    assertNull(root.list());
    assertNull(root.set());
    assertNull(root.collection());
    assertNull(root.map());
  }

  @Test
  void null_CollectionDefaultValueNullWhenUndefined() {
    String json = // language=JSON
        """
        {}
        """;
    CollectionDefaultValueRoot root = ofRoot(CollectionDefaultValueRoot.class, asJsonInput(json));
    assertEquals(List.of(), root.list());
    assertEquals(Set.of(1, 2, 3), root.set());
    assertEquals(List.of("a", "b", "c"), root.collection());
    assertEquals(Map.of("a", "b"), root.map());
  }
}
