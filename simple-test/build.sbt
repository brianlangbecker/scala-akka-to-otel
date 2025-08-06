ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "simple-test",
    libraryDependencies ++= Seq(
      "io.kamon" %% "kamon-bundle" % "2.7.0",
      "io.kamon" %% "kamon-core" % "2.7.0",
      "io.kamon" %% "kamon-opentelemetry" % "2.7.0",
      "ch.qos.logback" % "logback-classic" % "1.4.11"
    )
  )