# Build stage
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.5_8_1.8.2_2.13.10 AS builder

WORKDIR /app

COPY build.sbt .
COPY project/ project/
RUN sbt update

COPY src/ src/
RUN sbt assembly

# Download Kanela agent
RUN wget -O kanela-agent.jar https://repo1.maven.org/maven2/io/kamon/kanela-agent/1.0.17/kanela-agent-1.0.17.jar

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/scala-2.13/notification-service-typed.jar .
COPY --from=builder /app/kanela-agent.jar .

EXPOSE 8083

CMD ["java", \
     "-javaagent:kanela-agent.jar", \
     "-jar", "notification-service-typed.jar"]