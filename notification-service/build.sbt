ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "notification-service",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.5",
      "com.typesafe.akka" %% "akka-stream" % "2.8.5",
      "com.typesafe.akka" %% "akka-http" % "10.5.3",
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.5.3",
      "com.typesafe.akka" %% "akka-slf4j" % "2.8.5",
      "io.kamon" %% "kamon-bundle" % "2.7.0",
      "io.kamon" %% "kamon-core" % "2.7.0",
      "io.kamon" %% "kamon-opentelemetry" % "2.7.0",
      "io.kamon" %% "kamon-akka" % "2.7.0",
      "io.kamon" %% "kamon-akka-http" % "2.7.0",
      "ch.qos.logback" % "logback-classic" % "1.4.11",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case PathList("reference.conf") => MergeStrategy.concat
      case PathList("application.conf") => MergeStrategy.concat
      case "logback.xml" => MergeStrategy.first
      case _ => MergeStrategy.first
    },
    assembly / assemblyJarName := "notification-service.jar"
  )