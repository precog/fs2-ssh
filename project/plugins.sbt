resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public")

addSbtPlugin("com.eed3si9n"    % "sbt-buildinfo" % "0.9.0")
addSbtPlugin("io.get-coursier" % "sbt-coursier"  % "1.1.0-M13-4")
addSbtPlugin("com.slamdata"    % "sbt-slamdata"  % "3.2.1")
