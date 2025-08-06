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

# Download OpenTelemetry Java agent
RUN wget -O opentelemetry-javaagent.jar https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.8.0/opentelemetry-javaagent.jar

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/scala-2.13/user-service.jar .
COPY --from=builder /app/opentelemetry-javaagent.jar .

EXPOSE 8080

CMD ["java", "-javaagent:opentelemetry-javaagent.jar", "-Dotel.service.name=user-service", "-Dotel.exporter.otlp.endpoint=http://otel-collector:4318", "-jar", "user-service.jar"]