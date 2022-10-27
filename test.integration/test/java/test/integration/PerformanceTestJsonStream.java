package test.integration;

import static java.lang.System.currentTimeMillis;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonInputStream;
import se.jbee.json.stream.JsonStream;

class PerformanceTestJsonStream {

  interface Data {
    String id();

    String name();

    Iterator<Entry> entries();
  }

  interface Entry {
    int a();

    int b();
  }

  public static void main(String[] args) throws IOException {
    int n = 50_000_000;
    File file = Path.of("").resolve("data.json").toFile();
    try (BufferedWriter out = new BufferedWriter(new FileWriter(file))) {
      out.append("{\"name\":\"foo\", \"id\":\"123456\", \"entries\":[");
      int v = 10_000;
      int inc = 1;
      for (int i = 0; i < n; i++) {
        if (i > 0) out.append(",");
        out.append("\n{\"a\":");
        v += inc;
        out.append(String.valueOf(v)).append(", \"b\":");
        v += inc;
        out.append(String.valueOf(v)).append("}");
        if (v > 100_000 || v <= 10_000) inc *= -1;
      }
      out.append("]}");
    }
    System.out.println(Files.size(file.toPath()));
  }

  @Test
  void testPerformance() throws IOException {
    File input = Path.of("").resolve("data.json").toFile();

    assumeTrue(input.exists(), "input does not exist, abort performance testing: " + input);

    long avg = 0;
    int n = 0;
    long startMs = currentTimeMillis();

    Data data = JsonStream.ofRoot(Data.class, JsonInputStream.of(new FileInputStream(input)));
    Iterator<Entry> entries = data.entries();
    while (entries.hasNext()) {
      Entry e = entries.next();
      avg += (e.a() + e.b()) / 2;
      n++;
    }
    avg /= n;

    long duration = currentTimeMillis() - startMs;
    assertTrue(avg >= 50000 && avg <= 56000, "was " + avg);
    long fileSizeMB = Files.size(input.toPath()) / (1024 * 1024);
    System.out.printf("File is %d MB in size%n", fileSizeMB);
    System.out.printf(
        "Throughput was %d MB/sec or %d entries/sec%n",
        fileSizeMB * 1000 / duration, n * 1000L / duration);
    System.out.printf("%d ns/entry%n", duration * 1000_0000 / n);
  }
}
