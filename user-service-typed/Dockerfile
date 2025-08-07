# Build stage
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.9_9_1.9.7_2.13.12 as builder

WORKDIR /app

# Copy build files
COPY build.sbt .
COPY project/ project/
RUN sbt update

# Copy source code
COPY src/ src/
RUN sbt assembly

# Download Kanela agent
RUN wget -O kanela-agent.jar https://repo1.maven.org/maven2/io/kamon/kanela-agent/1.0.17/kanela-agent-1.0.17.jar

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/scala-2.13/user-service-typed.jar .
COPY --from=builder /app/kanela-agent.jar .

EXPOSE 8082

CMD ["java", \
     "-javaagent:kanela-agent.jar", \
     "-jar", "user-service-typed.jar"]