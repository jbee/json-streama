package project;

import run.bach.Project;

public class ProjectComposer implements Project.Composer {
  @Override
  public Project composeProject(Project project) {
    return project
        .withRequiresModule("org.junit.jupiter")
        .withRequiresModule("org.junit.platform.console")
        .withRequiresModule("org.junit.platform.jfr")
        ;
  }
}
