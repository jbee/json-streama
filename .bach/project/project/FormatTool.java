package project;

import java.io.PrintWriter;
import java.util.spi.ToolProvider;
import run.duke.Tooling;
import run.duke.Workbench;

public record FormatTool(Workbench workbench) implements Tooling {

  public FormatTool() {
    this(Workbench.inoperative());
  }

  @Override
  public ToolProvider provider(Workbench workbench) {
    return new FormatTool(workbench);
  }

  @Override
  public String name() {
    return "format";
  }

  @Override
  public int run(PrintWriter out, PrintWriter err, String... args) {
    run("format@1.15.0", format -> format.with("--replace").withFindFiles("**.java"));
    return 0;
  }
}
