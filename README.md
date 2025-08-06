# Scala Akka to OpenTelemetry Demo

A demonstration of distributed tracing with Scala, Akka, and OpenTelemetry using Kamon instrumentation, exporting traces to Honeycomb.

## Architecture

This project consists of two microservices that communicate with each other:

- **User Service** (Port 8080): Manages user creation and retrieval
- **Notification Service** (Port 8081): Handles notification sending

Both services are instrumented with Kamon and export telemetry data to Honeycomb via OpenTelemetry Collector.

## Services Overview

### User Service
- `GET /api/users/{id}` - Retrieve a user by ID
- `POST /api/users?name={name}` - Create a new user and send welcome notification
- `GET /health` - Health check endpoint

### Notification Service
- `POST /api/notifications` - Send a notification
- `GET /health` - Health check endpoint

## Prerequisites

- Docker & Docker Compose
- Honeycomb account and API key
- SBT (for local development)
- Scala 2.13
- Java 17+

## Quick Start

### 1. Clone and Setup

```bash
git clone <your-repo>
cd scala-akka-to-otel
```

### 2. Configure Honeycomb

```bash
cp .env.example .env
# Edit .env and add your Honeycomb API key
echo "HONEYCOMB_API_KEY=your_api_key_here" > .env
```

### 3. Start the Services

```bash
# Start all services with Docker Compose
docker-compose up --build

# Or start infrastructure only and run services locally
docker-compose up otel-collector prometheus
```

### 4. Test the Services

```bash
# Create a user (triggers notification service call)
curl -X POST "http://localhost:8080/api/users?name=Alice"

# Get a user (will return UserNotFound for now, but creates trace)
curl "http://localhost:8080/api/users/123"

# Health checks
curl "http://localhost:8080/health"
curl "http://localhost:8081/health"
```

## Local Development

### Running Services Locally

**Terminal 1 - Start infrastructure:**
```bash
docker-compose up otel-collector prometheus
```

**Terminal 2 - Start User Service:**
```bash
cd scala-akka-to-otel
sbt run
```

**Terminal 3 - Start Notification Service:**
```bash
cd scala-akka-to-otel/notification-service  
sbt run
```

### Project Structure

```
scala-akka-to-otel/
├── src/main/scala/com/example/
│   └── AkkaApp.scala              # User service implementation
├── notification-service/
│   └── src/main/scala/com/example/notification/
│       └── NotificationApp.scala  # Notification service implementation
├── otel-collector-config.yaml     # OpenTelemetry Collector configuration
├── docker-compose.yml             # Multi-service orchestration
├── prometheus.yml                 # Prometheus configuration
└── README.md
```

## OpenTelemetry Integration

### Instrumentation

Both services use **Kamon** for automatic instrumentation:

- **Akka Actor tracing**: Automatically traces actor message processing
- **Akka HTTP tracing**: Traces incoming HTTP requests and outgoing HTTP calls
- **System metrics**: CPU, memory, and JVM metrics

### Configuration

Key Kamon configuration in `application.conf`:

```hocon
kamon {
  environment {
    service = "user-service"  # or "notification-service"
  }
  
  opentelemetry {
    endpoint = "http://localhost:4317"
    protocol = "grpc"
  }
  
  modules {
    akka-actor.enabled = yes
    akka-http.enabled = yes
  }
}
```

### Distributed Tracing Flow

When you create a user:

1. **HTTP Request** → User Service receives POST request
2. **Actor Processing** → UserActor creates user
3. **HTTP Call** → User Service calls Notification Service
4. **Actor Processing** → NotificationActor processes notification
5. **HTTP Response** → Response flows back through the chain

All steps are automatically traced and correlated via trace context propagation.

## Deep Dive: User Creation Flow (Scala/Akka Perspective)

### Request Flow Architecture

```
HTTP POST /api/users?name=Alice
         ↓
   Akka HTTP Route
         ↓
   Ask Pattern → UserActor
         ↓
   Actor Message Processing
         ↓
   HTTP Client Call → Notification Service
         ↓
   Response Chain Back
```

### 1. HTTP Request Reception

**Location**: `src/main/scala/com/example/AkkaApp.scala:110-131`

```scala
post {
  parameter("name") { name =>
    logger.info(s"POST request to create user: $name")
    complete {
      import akka.actor.typed.scaladsl.AskPattern._
      val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.CreateUser(name, _))
      // ... rest of processing
    }
  }
}
```

**What happens:**
- Akka HTTP extracts the `name` parameter from query string
- Creates a `Future` using the **Ask Pattern** to communicate with the actor
- The ask pattern provides a temporary `ActorRef` for the response

### 2. Actor Message Processing

**Location**: `src/main/scala/com/example/AkkaApp.scala:49-56`

```scala
case CreateUser(name, replyTo) =>
  val id = java.util.UUID.randomUUID().toString
  val user = User(id, name)
  context.log.info(s"Creating user: $user")
  replyTo ! UserCreated(user)
  behavior(users + (id -> user))
```

**Akka Actor Concepts:**
- **Immutable State**: `behavior(users + (id -> user))` creates new behavior with updated user map
- **Message-Driven**: `CreateUser` is a sealed trait message processed by pattern matching
- **Actor Context**: `context.log` provides logging within actor context
- **Tell Pattern**: `replyTo !` sends response back to asking thread

### 3. Future Composition & Service Call

**Location**: `src/main/scala/com/example/AkkaApp.scala:116-128`

```scala
future.flatMap {
  case UserActor.UserCreated(user) => 
    logger.info(s"User created, sending welcome notification")
    sendNotification(user.id, s"Welcome ${user.name}! Your account has been created.").map { notificationResponse =>
      logger.info(s"Notification sent: ${notificationResponse.id}")
      HttpResponse(StatusCodes.Created, entity = s"Created user: ${user.name} with id: ${user.id}. Notification sent: ${notificationResponse.id}")
    }.recover {
      case ex =>
        logger.error("Failed to send notification", ex)
        HttpResponse(StatusCodes.Created, entity = s"Created user: ${user.name} with id: ${user.id}. Notification failed.")
    }
  case _ => Future.successful(HttpResponse(StatusCodes.InternalServerError))
}
```

**Scala Future Concepts:**
- **flatMap**: Chains futures together (Future[UserResponse] → Future[HttpResponse])
- **Pattern Matching**: Handles different response types from actor
- **Error Handling**: `.recover` handles HTTP call failures gracefully
- **Immutable Data**: All case classes are immutable

### 4. HTTP Client Call

**Location**: `src/main/scala/com/example/AkkaApp.scala:81-92`

```scala
def sendNotification(userId: String, message: String): Future[NotificationResponse] = {
  val request = NotificationRequest(userId, message, "email")
  val httpRequest = HttpRequest(
    method = HttpMethods.POST,
    uri = "http://localhost:8081/api/notifications",
    entity = HttpEntity(ContentTypes.`application/json`, request.toJson.compactPrint)
  )
  
  http.singleRequest(httpRequest).flatMap { response =>
    Unmarshal(response.entity).to[NotificationResponse]
  }
}
```

**Akka HTTP Client Concepts:**
- **Connection Pooling**: `http.singleRequest` uses Akka's connection pool
- **JSON Serialization**: Spray JSON converts case classes to JSON
- **Reactive Streams**: Response entity is unmarshalled asynchronously
- **Non-blocking**: Entire flow is asynchronous with Future-based composition

### 5. Notification Service Processing

**Location**: `notification-service/src/main/scala/com/example/notification/NotificationApp.scala:35-50`

```scala
case SendNotification(request, replyTo) =>
  context.log.info(s"Sending notification to user ${request.userId} via ${request.channel}: ${request.message}")
  
  // Simulate notification processing delay
  Thread.sleep(100)
  
  val response = NotificationResponse(
    id = java.util.UUID.randomUUID().toString,
    status = "sent",
    timestamp = System.currentTimeMillis()
  )
  
  context.log.info(s"Notification sent with id: ${response.id}")
  replyTo ! response
  Behaviors.same
```

**Actor Lifecycle:**
- **Behaviors.same**: Returns same behavior (stateless in this case)
- **Blocking Operation**: `Thread.sleep(100)` simulates I/O (not recommended in production)
- **Immutable Messages**: All case classes are immutable and serializable

### Key Scala/Akka Patterns Used

#### 1. Ask Pattern
```scala
val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.CreateUser(name, _))
```
- Converts actor communication to Future-based API
- Timeout handling built-in
- Type-safe with typed actors

#### 2. Typed Actors
```scala
implicit val system: ActorSystem[UserActor.Command] = ActorSystem(UserActor(), "user-system")
```
- Compile-time type safety for messages
- Protocol defined by sealed traits
- Better IDE support and refactoring safety

#### 3. Functional Error Handling
```scala
.recover {
  case ex =>
    logger.error("Failed to send notification", ex)
    HttpResponse(StatusCodes.Created, entity = s"User created but notification failed.")
}
```
- Graceful degradation: user creation succeeds even if notification fails
- Functional composition with Future combinators

#### 4. Immutable State Management
```scala
def behavior(users: Map[String, User]): Behavior[Command] = {
  // Process message and return new behavior with updated state
  behavior(users + (id -> user))
}
```
- No mutable state in actors
- State changes by returning new behavior
- Thread-safe by design

### Concurrency Model

1. **Single-threaded actors**: Each actor processes one message at a time
2. **Asynchronous boundaries**: HTTP calls don't block actor threads
3. **Future composition**: Chains async operations without blocking
4. **Backpressure**: Akka Streams handle flow control automatically
5. **Fault tolerance**: Actor supervision and Future error handling

### OpenTelemetry Integration Points

- **HTTP spans**: Automatically created for incoming/outgoing HTTP requests
- **Actor spans**: Kamon creates spans for actor message processing
- **Future tracing**: Trace context propagated through Future chains
- **Service boundaries**: Trace context sent in HTTP headers between services

This architecture demonstrates reactive, non-blocking, fault-tolerant microservices using Akka's actor model with comprehensive observability.

## Observability Stack

### Honeycomb
- **Traces**: View distributed traces across services
- **Dataset**: `scala-akka-to-otel`
- **URL**: https://ui.honeycomb.io/

### Prometheus (Local)
- **Metrics**: System and application metrics
- **URL**: http://localhost:9090

### OpenTelemetry Collector
- **OTLP gRPC**: Port 4317
- **OTLP HTTP**: Port 4318
- **Prometheus metrics**: Port 8889

## Example Traces

After running the test commands, you should see traces in Honeycomb showing:

- **Service**: `user-service` and `notification-service`
- **Operations**: 
  - `POST /api/users`
  - `POST /api/notifications`
  - Actor message processing
- **Spans**: HTTP requests, actor operations, and service calls
- **Attributes**: HTTP status codes, user IDs, error information

## Troubleshooting

### Services Won't Start
```bash
# Check if ports are in use
lsof -i :8080
lsof -i :8081
lsof -i :4317

# View service logs
docker-compose logs user-service
docker-compose logs notification-service
docker-compose logs otel-collector
```

### No Traces in Honeycomb
1. Verify your `HONEYCOMB_API_KEY` in `.env`
2. Check OpenTelemetry Collector logs: `docker-compose logs otel-collector`
3. Ensure services can reach collector: `curl http://localhost:4317`
4. Verify Kamon configuration in `application.conf`

### Service Communication Issues
```bash
# Test notification service directly
curl -X POST "http://localhost:8081/api/notifications" \
  -H "Content-Type: application/json" \
  -d '{"userId":"test","message":"test message","channel":"email"}'

# Check Docker network connectivity
docker-compose exec user-service ping notification-service
```

## Customization

### Adding More Services
1. Create new service directory with `build.sbt` and Scala code
2. Add Kamon dependencies and configuration
3. Update `docker-compose.yml` with new service
4. Update existing services to call new service if needed

### Different Observability Backends
Modify `otel-collector-config.yaml` exporters section:

```yaml
exporters:
  # For Jaeger
  jaeger:
    endpoint: http://jaeger:14250
    tls:
      insecure: true
      
  # For other OTLP-compatible backends
  otlp/custom:
    endpoint: https://your-backend.com:443
    headers:
      authorization: "Bearer your-token"
```

## Dependencies

### Core Dependencies
- **Akka Actor Typed**: 2.8.5
- **Akka HTTP**: 10.5.3
- **Kamon Core**: 2.6.5
- **Kamon OpenTelemetry**: 2.6.5
- **Kamon Akka**: 2.6.5

### Build Tools
- **SBT**: 1.9.6
- **Scala**: 2.13.12

## License

MIT License - see LICENSE file for details.