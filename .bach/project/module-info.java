import run.bach.ProjectInfo;
import run.bach.ProjectInfo.Space;

@ProjectInfo(
    name = "json-stream",
    spaces = {
      @Space(name = "main", modules = "se.jbee.json.stream"),
      @Space(name = "test", requires = "main", modules = "test.integration")
    })
module project {
  requires run.bach;

  provides run.duke.ToolOperator with
      project.FormatTool;
}
