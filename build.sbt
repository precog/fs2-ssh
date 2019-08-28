import scala.collection.Seq

homepage in ThisBuild := Some(url("https://github.com/slamdata/fs2-ssh"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/fs2-ssh"),
  "scm:git@github.com:slamdata/fs2-ssh.git"))

val SshdVersion = "2.3.0"

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
    libraryDependencies ++= Seq(
      "org.apache.sshd" % "sshd-core" % SshdVersion,
      "org.apache.sshd" % "sshd-netty" % SshdVersion,

      "org.typelevel" %% "cats-effect" % "1.4.0",
      "co.fs2"        %% "fs2-io"      % "1.0.5",

      "org.specs2" %% "specs2-core" % "4.7.0" % Test),

    performMavenCentralSync := true,
    publishAsOSSProject := true,

    initialCommands := """
      | import scala._, Predef._
      |
      | import cats.effect._
      | import cats.implicits._
      |
      | import fs2._
      | import fs2.io.ssh._
      |
      | import scala.concurrent.ExecutionContext
      |
      | import java.nio.file.Paths
      |
      | val blockerR = Blocker[IO]
      | implicit val cs = IO.contextShift(ExecutionContext.global)
      |
      | val auth = Auth.Key(Paths.get("id_rsa_testing"), None)""".stripMargin,

    Compile / console / scalacOptions += "-Ydelambdafy:inline")
  .enablePlugins(AutomateHeaderPlugin)
