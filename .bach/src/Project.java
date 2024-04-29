import run.bach.workflow.Builder;
import run.bach.workflow.Structure;
import run.bach.workflow.Workflow;

record Project(Workflow workflow) implements Builder {
  static Project ofCurrentWorkingDirectory() {
    var main =
        new Structure.Space("main")
            .withModule("se.jbee.json.stream", "se.jbee.json.stream/main/java/module-info.java");
    var test =
        new Structure.Space("test", main)
            .withModule("test.integration", "test.integration/test/java/module-info.java");
    return new Project(
        Workflow.ofCurrentWorkingDirectory()
            .withName("streama")
            .withVersion("0-ea")
            .with(main)
            .with(test));
  }
}
