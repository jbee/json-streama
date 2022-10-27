package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static se.jbee.json.stream.JsonStream.ofRoot;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonProperty;
import se.jbee.json.stream.JsonStream;
import se.jbee.json.stream.JsonToJava;

/**
 * Tests the {@code null} related aspects and features of the {@link JsonToJava#DEFAULT} as well as
 * the related features of {@link JsonProperty} to adjust them.
 */
class TestJsonStreamNulls {

  interface CollectionRoot {
    List<String> list();

    Set<String> set();

    Collection<String> collection();

    Map<String, String> map();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "intValue": null,
      "longValue": null,
      "doubleValue":null,
      "floatValue": null,
      "booleanValue": null,
      "charValue": null
      }
      """
      })
  void null_PrimitivesHaveNullValue(String json) {
    interface PrimitiveRoot {
      int intValue();

      long longValue();

      double doubleValue();

      float floatValue();

      boolean booleanValue();

      char charValue();
    }
    PrimitiveRoot root = ofRoot(PrimitiveRoot.class, JsonInputStream.of(json));
    assertEquals(0, root.intValue());
    assertEquals(0L, root.longValue());
    assertEquals(0d, root.doubleValue());
    assertEquals(0f, root.floatValue());
    assertFalse(root.booleanValue());
    assertEquals((char) 0, root.charValue());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "integerValue": null,
      "longValue": null,
      "doubleValue":null,
      "floatValue": null,
      "booleanValue": null,
      "characterValue": null
      }
      """
      })
  void null_WrappersAreNull(String json) {
    interface WrapperRoot {
      Integer integerValue();

      Long longValue();

      Double doubleValue();

      Float floatValue();

      Boolean booleanValue();

      Character characterValue();
    }
    WrapperRoot root = ofRoot(WrapperRoot.class, JsonInputStream.of(json));
    assertNull(root.integerValue());
    assertNull(root.longValue());
    assertNull(root.doubleValue());
    assertNull(root.floatValue());
    assertNull(root.booleanValue());
    assertNull(root.characterValue());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "list": null,
      "set": null,
      "collection": null,
      "map": null
      }
      """
      })
  void null_CollectionsAreEmptyWhenDefinedNull(String json) {
    CollectionRoot root = ofRoot(CollectionRoot.class, JsonInputStream.of(json));
    assertEquals(List.of(), root.list());
    assertEquals(Set.of(), root.set());
    assertEquals(List.of(), root.collection());
    assertEquals(Map.of(), root.map());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "list": null,
      "set": null,
      "collection": null,
      "map": null
      }
      """
      })
  void null_CollectionsConfigNull(String json) {
    JsonToJava toJava =
        JsonToJava.DEFAULT
            .with(List.class, null, List::of)
            .with(Set.class, null, Set::of)
            .with(Collection.class, null, List::of)
            .with(Map.class, null, e -> Map.of("value", e));

    CollectionRoot root = ofRoot(CollectionRoot.class, JsonInputStream.of(json), toJava);
    assertNull(root.list());
    assertNull(root.set());
    assertNull(root.collection());
    assertNull(root.map());
  }

  /**
   * Undefined or JSON null in the input translates to Java {@code null} because of {@link
   * JsonProperty#retainNulls()} which has higher precedence than any mapping
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "list": null,
      "set": null,
      "collection": null,
      "map": null
      }
      """
      })
  void null_CollectionRetainNulls(String json) {
    interface CollectionNullRoot {
      @JsonProperty(retainNulls = true)
      List<String> list();

      @JsonProperty(retainNulls = true)
      Set<String> set();

      @JsonProperty(retainNulls = true)
      Collection<String> collection();

      @JsonProperty(retainNulls = true)
      Map<String, String> map();
    }
    CollectionNullRoot root = ofRoot(CollectionNullRoot.class, JsonInputStream.of(json));
    assertNull(root.list());
    assertNull(root.set());
    assertNull(root.collection());
    assertNull(root.map());
  }

  /**
   * Undefined or JSON null in the input translates to the annotated {@link
   * JsonProperty#defaultValue()}
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "list":null,
      "set":null,
      "map":null,
      "collection":null
      }
      """
      })
  void null_CollectionDefaultValue(String json) {
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
    CollectionDefaultValueRoot root =
        ofRoot(CollectionDefaultValueRoot.class, JsonInputStream.of(json));
    assertEquals(List.of(), root.list());
    assertEquals(Set.of(1, 2, 3), root.set());
    assertEquals(List.of("a", "b", "c"), root.collection());
    assertEquals(Map.of("a", "b"), root.map());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "stream": null,
      "anotherStream": null
      }
      """
      })
  void null_StreamIsEmpty(String json) {
    interface StreamRoot {
      Stream<String> stream();

      Stream<String> anotherStream();
    }
    StreamRoot root = ofRoot(StreamRoot.class, JsonInputStream.of(json));
    Stream<String> stream = root.stream();
    assertEquals(Stream.empty().toList(), stream.toList());
    assertNotSame(stream, root.anotherStream(), "empty stream cannot be used as constant");
    assertNotSame(
        stream,
        ofRoot(StreamRoot.class, JsonInputStream.of(json)).stream(),
        "empty stream cannot be used as constant");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "iterator": null
      }
      """
      })
  void null_IteratorIsEmpty(String json) {
    interface IteratorRoot {
      Iterator<Integer> iterator();
    }
    IteratorRoot root = ofRoot(IteratorRoot.class, JsonInputStream.of(json));
    Iterator<Integer> iter = root.iterator();
    assertFalse(iter.hasNext());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {
      "stream": null
      }
      """
      })
  void null_StreamConfigNull(String json) {
    interface StreamNullRoot {
      Stream<String> stream();
    }
    JsonToJava toJava = JsonToJava.DEFAULT.with(Stream.class, null, Stream::of);
    StreamNullRoot root = ofRoot(StreamNullRoot.class, JsonInputStream.of(json), toJava);
    assertNull(root.stream(), "a stream can be configured to be null");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
        {}
        """,
        // language=JSON
        """
        {
        "stream": null
        }
        """
      })
  void null_MappedStreamDefaultParameter(String json) {
    interface StreamDefaultParameterRoot {
      Stream<String> stream(Stream<String> defaultValue);
    }
    StreamDefaultParameterRoot root =
        ofRoot(StreamDefaultParameterRoot.class, JsonInputStream.of(json));
    assertEquals(Stream.of("1").toList(), root.stream(Stream.of("1")).toList());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
          {}
          """,
        // language=JSON
        """
          {
          "stream": null
          }
          """
      })
  void null_MappedStreamDefaultParameterSupplier(String json) {
    interface StreamDefaultParameterRoot {
      Stream<String> stream(Supplier<Stream<String>> defaultValue);
    }
    StreamDefaultParameterRoot root =
        ofRoot(StreamDefaultParameterRoot.class, JsonInputStream.of(json));
    assertEquals(Stream.of("1").toList(), root.stream(() -> Stream.of("1")).toList());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {
      "stream":[]
      }
      """
      })
  void null_StreamConfigNull_Empty(String json) {
    interface StreamNullRoot {
      Stream<String> stream();
    }
    JsonToJava toJava = JsonToJava.DEFAULT.with(Stream.class, null, Stream::of);
    StreamNullRoot root = ofRoot(StreamNullRoot.class, JsonInputStream.of(json), toJava);
    String msg =
        "an empty array [] becomes an empty stream even if a null or undefined stream is configured"
            + " to be null";
    assertEquals(List.of(), root.stream().toList(), msg);
  }

  @Test
  void null_MappedStreamNullForElements() {
    String json = // language=JSON
        """
        ["hello", null, "again"]
        """;
    Stream<String> root = JsonStream.of(String.class, JsonInputStream.of(json));
    assertEquals(Arrays.asList("hello", null, "again"), root.toList());
    // same but with null mapped to empty String
    JsonToJava mapping = JsonToJava.DEFAULT.with(String.class, nulls -> nulls.mapNull(""));
    root = JsonStream.of(String.class, JsonInputStream.of(json), mapping);
    assertEquals(List.of("hello", "", "again"), root.toList());
  }

  @Test
  void null_MappedStreamRetainNullsForElements() {
    String json = // language=JSON
        """
        {
        "stream": ["hello", null, "again"]
        }
        """;
    interface StreamRetainNullsRoot {
      @JsonProperty(retainNulls = true)
      Stream<String> stream();
    }
    StreamRetainNullsRoot root = ofRoot(StreamRetainNullsRoot.class, JsonInputStream.of(json));
    assertEquals(Arrays.asList("hello", null, "again"), root.stream().toList());
  }

  @Test
  void null_MappedStreamDefaultValueForElements() {
    String json = // language=JSON
        """
        {
        "stream": ["hello", null, "again"]
        }
        """;
    interface StreamDefaultValueRoot {
      @JsonProperty(defaultValue = "\"empty\"")
      Stream<String> stream();
    }
    StreamDefaultValueRoot root = ofRoot(StreamDefaultValueRoot.class, JsonInputStream.of(json));
    assertEquals(List.of("hello", "empty", "again"), root.stream().toList());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // language=JSON
        """
      {}
      """,
        // language=JSON
        """
      {"element": null}
      """
      })
  void null_ProxyObject(String json) {
    interface Element {
      String name();
    }
    interface ProxyObjectRoot {
      Element element();
    }
    ProxyObjectRoot root = ofRoot(ProxyObjectRoot.class, JsonInputStream.of(json));
    assertNull(root.element());
  }
}
