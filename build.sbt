import scala.collection.Seq

homepage in ThisBuild := Some(url("https://github.com/slamdata/fs2-ssh"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/fs2-ssh"),
  "scm:git@github.com:slamdata/fs2-ssh.git"))

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  publishArtifact in (Test, packageBin) := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "fs2-ssh")
  .settings(
    performMavenCentralSync := false,
    publishAsOSSProject := true)
  .enablePlugins(AutomateHeaderPlugin)
