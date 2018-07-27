name := "kraps-rpc"

scalaVersion := "2.11.8"

lazy val root = (project in file(".")).aggregate(krapsRpc)

lazy val commonSettings = Seq(
  name := "kraps-rpc",
  organization := "neoremind",
  version := "1.0.1-SNAPSHOT",
  scalaVersion := "2.11.8",
  publishMavenStyle := true,
  organizationName := "neoremind",
  organizationHomepage := Some(url("http://neoremind.net")),
  publishArtifact in Test := false,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Compile, packageSrc) := true,
  publishTo := {
    // if (appVersion.endsWith("-SNAPSHOT"))
    Some("nexus-release" at "https://oss.sonatype.org/content/repositories/snapshots/")
    // else
    // Some("nexus-release" at "https://oss.sonatype.org/service/local/staging/deploy/maven2/")
  },
  resolvers ++= Seq("tims-repo" at "http://timezra.github.com/maven/releases")
)

lazy val krapsRpc = (project in file("kraps-core"))
  .settings(commonSettings: _*)
  .settings(
    name := "kraps-core",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % "1.7.7",
      "org.slf4j" % "slf4j-log4j12" % "1.7.7",
      "com.google.guava" % "guava" % "15.0",
      "org.apache.spark" %% "spark-network-common" % "2.1.0",
      "de.ruedigermoeller" % "fst" % "2.50",
      "org.scalatest" %% "scalatest" % "2.2.6" % "test",
      "org.scalamock" %% "scalamock-scalatest-support" % "3.2.2" % "test",
      "org.hamcrest" % "hamcrest-core" % "1.3" % "test",
      "org.hamcrest" % "hamcrest-library" % "1.3" % "test",
      "junit" % "junit" % "4.11" % "test"
    )
  )
