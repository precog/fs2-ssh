resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.bintrayIvyRepo("slamdata-inc", "sbt-plugins")

addSbtPlugin("com.slamdata" % "sbt-slamdata" % "6.2.7")
