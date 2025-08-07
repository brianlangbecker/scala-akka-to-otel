ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .settings(
    name := "notification-service-typed",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % "2.8.5",
      "com.typesafe.akka" %% "akka-stream" % "2.8.5",
      "com.typesafe.akka" %% "akka-http" % "10.5.3",
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.5.3",
      "com.typesafe.akka" %% "akka-slf4j" % "2.8.5",
      
      // OpenTelemetry dependencies for Typed Akka
      "io.opentelemetry" % "opentelemetry-api" % "1.31.0",
      "io.opentelemetry" % "opentelemetry-sdk" % "1.31.0",
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % "1.31.0",
      "io.opentelemetry.instrumentation" % "opentelemetry-instrumentation-api" % "1.31.0",
      "io.opentelemetry.semconv" % "opentelemetry-semconv" % "1.21.0-alpha",
      
      // Akka OpenTelemetry extension (replaces Kamon for typed actors)
      "com.lightbend.akka" %% "akka-diagnostics" % "2.0.0",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.11",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("reference.conf") => MergeStrategy.concat
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    },
    assembly / assemblyJarName := "notification-service-typed.jar"
  )