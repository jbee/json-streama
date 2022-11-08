package project;

import run.bach.ToolOperator;

public record FormatTool(String name) implements ToolOperator {
  public FormatTool() {
    this("format");
  }

  @Override
  public void run(Operation operation) {
    operation.run("format@1.15.0", format -> format.with("--replace").withFindFiles("**.java"));
  }
}
