FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.5_8_1.8.2_2.13.10

WORKDIR /app

COPY build.sbt .
RUN sbt update

COPY src/ src/
RUN sbt compile

# Download Kanela agent
RUN wget -O /app/kanela-agent.jar https://repo1.maven.org/maven2/io/kamon/kanela-agent/1.0.17/kanela-agent-1.0.17.jar

# Use the Kanela agent when running the application
CMD ["sbt", "run", "-J-javaagent:/app/kanela-agent.jar"]