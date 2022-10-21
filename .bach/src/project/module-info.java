module project {
  requires run.bach;

  provides run.bach.Project.Composer with
      project.ProjectComposer;
  provides run.bach.ToolOperator with
      project.FormatTool;
}
