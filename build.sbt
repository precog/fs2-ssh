import sbt.TestFrameworks.Specs2
import scala.collection.Seq

ThisBuild / crossScalaVersions := Seq("2.12.12", "2.13.3")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.head

ThisBuild / githubRepository := "fs2-ssh"

ThisBuild / homepage := Some(url("https://github.com/precog/fs2-ssh"))

ThisBuild / scmInfo := Some(ScmInfo(
  url("https://github.com/precog/fs2-ssh"),
  "scm:git@github.com:precog/fs2-ssh.git"))

ThisBuild / logBuffered := false

val CatsEffectVersion = "3.4.11"
val CatsMtlVersion = "1.3.0"
val Fs2Version = "3.6.1"
val NettyVersion = "4.1.100.Final"
val SshdVersion = "2.13.2"

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  Test / packageBin / publishArtifact := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(name := "fs2-ssh")
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.sshd" % "sshd-core"  % SshdVersion,
      "org.apache.sshd" % "sshd-netty" % SshdVersion,

      "org.typelevel" %% "cats-effect"   % CatsEffectVersion,
      "co.fs2"        %% "fs2-core"      % Fs2Version,
      "org.typelevel" %% "cats-mtl" % CatsMtlVersion,

      // apparently vertically aligning this chunk causes sbt to freak out... for reasons
      "org.specs2" %% "specs2-core" % "4.19.2"  % Test,
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.19.0" % Test,
      "com.whisk" %% "docker-testkit-specs2" % "0.9.9" % Test,
      "com.whisk" %% "docker-testkit-impl-spotify" % "0.9.9" % Test,
      "org.http4s" %% "http4s-okhttp-client" % "0.23.11" % Test,
      "co.fs2" %% "fs2-io" % Fs2Version % Test),
    dependencyOverrides ++= Seq(
      "io.netty" % "netty-buffer" % NettyVersion,
      "io.netty" % "netty-codec" % NettyVersion,
      "io.netty" % "netty-common" % NettyVersion,
      "io.netty" % "netty-handler" % NettyVersion,
      "io.netty" % "netty-resolver" % NettyVersion,
      "io.netty" % "netty-transport" % NettyVersion,
      "io.netty" % "netty-codec-http" % NettyVersion,
      "io.netty" % "netty-codec-socks" % NettyVersion,
      "io.netty" % "netty-handler-proxy" % NettyVersion
    ),

    Test / scalacOptions += "-Yrangepos",
    Test / testOptions := Seq(Tests.Argument(Specs2, "exclude", "exclusive", "showtimes")),
    Test / parallelExecution := false,

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
      | import java.nio.file.Paths
      |
      | val auth = Auth.Key(Paths.get("id_rsa_testing"), None)""".stripMargin,

    Compile / console / scalacOptions += "-Ydelambdafy:inline")
