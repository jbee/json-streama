import com.github.sormuras.bach.Bach;
import com.github.sormuras.bach.Project;
import com.github.sormuras.bach.external.JUnit;
import com.github.sormuras.bach.workflow.WorkflowRunner;
import java.util.Set;

class build {
  public static void main(String[] args) {
    var project =
        Project.of("json-stream", "0-ea")
            .withSpaces(
                spaces ->
                    spaces
                        .withSpace("main", main -> main.withModule("se.jbee.json.stream/main/java"))
                        .withSpace(
                            "test",
                            Set.of("main"),
                            test -> test.withModule("test.integration/test/java")))
            .withExternals(ex -> ex.withExternalModuleLocator(JUnit.version("5.8.2")));

    try (var bach = new Bach()) {
      bach.logMessage("Build project %s".formatted(project.toNameAndVersion()));

      var runner = new WorkflowRunner(bach, project);
      runner.grabExternals();
      runner.compileSpaces();
      runner.executeTests();
    }
  }
}
