import sbt.TestFrameworks.Specs2
import scala.collection.Seq

ThisBuild / crossScalaVersions := Seq("2.12.10", "2.13.1")
ThisBuild / scalaVersion := "2.12.10"

homepage in ThisBuild := Some(url("https://github.com/slamdata/fs2-ssh"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/fs2-ssh"),
  "scm:git@github.com:slamdata/fs2-ssh.git"))

logBuffered in ThisBuild := false

val SshdVersion = "2.3.0"
val Fs2Version = "2.2.1"

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
      "org.apache.sshd" % "sshd-core"  % SshdVersion,
      "org.apache.sshd" % "sshd-netty" % SshdVersion,

      "org.typelevel" %% "cats-effect"   % "2.0.0",
      "co.fs2"        %% "fs2-core"      % Fs2Version,
      "org.typelevel" %% "cats-mtl-core" % "0.7.1",

      // apparently vertically aligning this chunk causes sbt to freak out... for reasons
      "org.specs2" %% "specs2-core" % "4.8.1"  % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.13.0" % Test,
      "com.whisk" %% "docker-testkit-specs2" % "0.9.9" % Test,
      "com.whisk" %% "docker-testkit-impl-spotify" % "0.9.9" % Test,
      "co.fs2" %% "fs2-io" % Fs2Version % Test),

    Test / scalacOptions += "-Yrangepos",
    Test / testOptions := Seq(Tests.Argument(Specs2, "exclude", "exclusive", "showtimes")),

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
