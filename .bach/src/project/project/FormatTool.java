package project;

import java.util.List;
import run.bach.Bach;
import run.bach.ToolOperator;

public record FormatTool(String name) implements ToolOperator {
  public FormatTool() {
    this("format");
  }

  @Override
  public void operate(Bach bach, List<String> arguments) {
    bach.run("format@1.15.0", format -> format.with("--replace").withFindFiles("**.java"));
  }
}
