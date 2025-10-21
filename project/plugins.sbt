credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env
    .get("GITHUB_ACTOR")
    .getOrElse(sys.error("Please define GITHUB_ACTOR")),
  sys.env.get("GITHUB_TOKEN").getOrElse(sys.error("Please define GITHUB_TOKEN"))
)

resolvers += "GitHub Package Registry" at "https://maven.pkg.github.com/precog/_"

addSbtPlugin("com.precog" % "sbt-precog-config" % "7.0.9")
