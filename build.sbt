ThisBuild / crossScalaVersions := Seq("2.13.16")
ThisBuild / scalaVersion := (ThisBuild / crossScalaVersions).value.head

ThisBuild / githubRepository := "fs2-ssh"

ThisBuild / homepage := Some(url("https://github.com/precog/fs2-ssh"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/precog/fs2-ssh"),
    "scm:git@github.com:precog/fs2-ssh.git"
  )
)

ThisBuild / logBuffered := false

val CatsEffectVersion = "3.6.3"
val CatsMtlVersion = "1.6.0"
val DockerClientVersion = "9.0.4"
val Fs2Version = "3.12.2"
val Http4sVersion = "0.23.32"
val Log4jVersion = "2.19.0"
val NettyVersion = "4.1.125.Final"
val SshdVersion = "2.12.1"
val MunitCatsEffectVersion = "2.1.0"
val MunitVersion = "1.2.1"
val TestContainersVersion = "0.43.0"

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(Test / packageBin / publishArtifact := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)

lazy val core = project
  .in(file("core"))
  .settings(name := "fs2-ssh")
  .settings(
    libraryDependencies ++= Seq(
      "org.apache.sshd" % "sshd-core" % SshdVersion,
      "org.apache.sshd" % "sshd-netty" % SshdVersion,
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2" %% "fs2-core" % Fs2Version,
      "org.typelevel" %% "cats-mtl" % CatsMtlVersion,

      // apparently vertically aligning this chunk causes sbt to freak out... for reasons
      "org.apache.logging.log4j" % "log4j-slf4j-impl" % Log4jVersion % Test,
      "org.mandas" % "docker-client" % DockerClientVersion % Test,
      "org.http4s" %% "http4s-ember-client" % Http4sVersion % Test,
      "co.fs2" %% "fs2-io" % Fs2Version % Test,
      "org.scalameta" %% "munit" % MunitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-core" % TestContainersVersion % Test
    ),
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
    Compile / console / scalacOptions += "-Ydelambdafy:inline"
  )
