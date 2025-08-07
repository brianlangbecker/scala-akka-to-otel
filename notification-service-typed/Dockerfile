# Build stage
FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.5_8_1.8.2_2.13.10 AS builder

WORKDIR /app

COPY build.sbt .
COPY project/ project/
RUN sbt update

COPY src/ src/
RUN sbt assembly

# Download OpenTelemetry Java agent
RUN wget -O opentelemetry-javaagent.jar https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/scala-2.13/notification-service-typed.jar .
COPY --from=builder /app/opentelemetry-javaagent.jar .

EXPOSE 8081

CMD ["java", \
     "-javaagent:opentelemetry-javaagent.jar", \
     "-Dotel.service.name=notification-service-typed", \
     "-Dotel.exporter.otlp.endpoint=http://otel-collector:4318", \
     "-jar", "notification-service-typed.jar"]