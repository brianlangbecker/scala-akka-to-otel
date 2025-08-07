# Scala Akka Typed to OpenTelemetry Demo

A demonstration of distributed tracing with Scala, **Akka Typed actors**, and OpenTelemetry using Kamon instrumentation, exporting traces to Honeycomb.

## 📖 Documentation

- **[README-CLASSIC.md](README-CLASSIC.md)** - Classic Akka actors implementation (better automatic tracing)
- **[README_AKKA_COMPARISON.md](README_AKKA_COMPARISON.md)** - Comprehensive comparison of Classic vs Typed, including bytecode injection differences
- **README.md** (this file) - Akka Typed implementation

> ⚠️ **Important**: Akka Typed actors have **limited automatic tracing** with Kamon. For comprehensive automatic tracing of actor message flows, consider using the Classic implementation. See the comparison document for detailed differences.

## Architecture

This project consists of two microservices that communicate with each other:

- **User Service Typed** (Port 8080): Manages user creation and retrieval using Akka Typed actors
- **Notification Service Typed** (Port 8081): Handles notification sending using Akka Typed actors

Both services are instrumented with Kamon and export telemetry data to Honeycomb via OpenTelemetry Collector.

## Services Overview

### User Service Typed
- `GET /api/users/{id}` - Retrieve a user by ID  
- `POST /api/users?name={name}` - Create a new user and send welcome notification
- `GET /api/test-typed` - Test typed actor functionality
- `GET /api/test-streams/{userId}` - Test Akka Streams processing
- `GET /api/test-circuit-breaker/{userId}` - Test circuit breaker pattern

### Notification Service Typed  
- `POST /api/notifications` - Send a notification

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
cd user-service-typed
sbt run
```

**Terminal 3 - Start Notification Service:**
```bash
cd notification-service-typed
sbt run
```

### Project Structure

```
scala-akka-to-otel/
├── user-service-typed/
│   └── src/main/scala/com/example/
│       └── AkkaApp.scala           # Typed user service implementation
├── notification-service-typed/
│   └── src/main/scala/com/example/notification/
│       └── NotificationApp.scala  # Typed notification service implementation
├── otel-collector-config.yaml     # OpenTelemetry Collector configuration
├── docker-compose.yml             # Multi-service orchestration
├── prometheus.yml                 # Prometheus configuration
├── README.md                      # Akka Typed (this file)
├── README-CLASSIC.md              # Classic Akka implementation
└── README_AKKA_COMPARISON.md      # Classic vs Typed comparison
```

## OpenTelemetry Integration

### Instrumentation

Both services use **Kamon** with **Kanela** for automatic instrumentation:

- **Kanela Agent**: Bytecode instrumentation agent that injects tracing code at runtime
- **Akka Actor tracing**: Limited automatic tracing (Typed actors require manual instrumentation)
- **Akka HTTP tracing**: Traces incoming HTTP requests and outgoing HTTP calls
- **Manual Instrumentation**: Typed actors require explicit span creation for detailed tracing

### Kanela Agent Configuration

The Kanela instrumentation agent is automatically loaded via Docker:

```dockerfile
# Downloaded in Dockerfile
RUN wget -O kanela-agent.jar https://repo1.maven.org/maven2/io/kamon/kanela-agent/1.0.17/kanela-agent-1.0.17.jar

# Started with JVM
CMD java -javaagent:kanela-agent.jar -jar user-service.jar
```

**What Kanela instruments with Typed actors:**
- Akka HTTP client/server calls → automatic HTTP spans  
- Future/Promise completions → automatic async context propagation
- Akka Streams processing → limited automatic stage spans
- Typed actor messages → **minimal automatic tracing** (see comparison doc)

### Kamon Configuration

Key Kamon configuration in `application.conf`:

```hocon
kamon {
  environment {
    service = "user-service-typed"  # or "notification-service-typed"
  }
  
  opentelemetry {
    endpoint = "http://localhost:4317"
    protocol = "grpc"
  }
  
  modules {
    akka-actor.enabled = yes  # Limited effectiveness with Typed actors
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
   Ask Pattern → ClassicUserActor (automatically traced)
         ↓
   Actor Message Processing:
   ├─ Tell → EmailActor (child actor - automatically traced)
   ├─ Tell → AuditActor (child actor - automatically traced)  
   ├─ Router → NotificationWorkerActor (load balanced - automatically traced)
   └─ Actor-based Event Processing:
       ├─ Ask → ValidationActor (automatically traced)
       └─ Ask → EnrichmentActor (automatically traced)
         ↓
   HTTP Client Call → Notification Service (automatically traced)
         ↓
   Ask Pattern → NotificationActor (automatically traced)
         ↓
   Response Chain Back (all automatically correlated)
```

### 1. HTTP Request Reception

**Location**: `user-service/src/main/scala/com/example/AkkaApp.scala:552-583`

```scala
post {
  parameter("name") { name =>
    logger.info(s"POST request to create user: $name")
    complete {
      val future: Future[Any] = (userActor ? ClassicUserActor.CreateUser(name))
        .mapTo[ClassicUserActor.UserCreated]
      // ... rest of processing with actor-based event processing
    }
  }
}
```

**What happens:**
- Akka HTTP extracts the `name` parameter from query string
- Uses **Classic Akka Ask Pattern** (`?`) to communicate with the actor
- Automatically traced by Kamon's Akka instrumentation

### 2. Classic Actor Message Processing

**Location**: `user-service/src/main/scala/com/example/AkkaApp.scala:243-256`

```scala
case CreateUser(name) =>
  val id = java.util.UUID.randomUUID().toString
  val newUser = User(id, name)
  users = users + (id -> newUser)
  log.info(s"Created user: $id, $name")
  
  // Child actor interactions - all automatically traced!
  emailActor ! SendWelcomeEmail(newUser.id, newUser.name)
  auditActor ! AuditEvent(s"User ${newUser.id} created", System.currentTimeMillis())
  
  // Router usage - automatically traced with routing info!
  notificationRouter.route(ProcessNotification(newUser.id, "user_created"), self)
  
  sender() ! UserCreated(newUser)
```

**Classic Akka Actor Concepts:**
- **Mutable State Management**: `users = users + (id -> newUser)` updates actor's internal state  
- **Message-Driven**: `CreateUser` case class message processed by pattern matching
- **Actor Logging**: `log.info` provides logging within classic actor context
- **Tell Pattern**: `!` sends fire-and-forget messages to child actors
- **Ask Pattern**: `sender() !` sends response back to original requester
- **Child Actor Supervision**: EmailActor and AuditActor are supervised children
- **Router Pattern**: Load balances messages across multiple worker instances

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

### OpenTelemetry Collector
- **OTLP gRPC**: Port 4317
- **OTLP HTTP**: Port 4318

## Example Traces

After running the test commands, you should see traces in Honeycomb showing:

- **Service**: `user-service-typed` and `notification-service-typed`
- **Operations**: 
  - `POST /api/users`
  - `POST /api/notifications`
  - Limited actor message processing (manual spans required)
- **Spans**: HTTP requests, manual actor spans, and service calls
- **Attributes**: HTTP status codes, user IDs, error information

## Troubleshooting

### Services Won't Start
```bash
# Check if ports are in use
lsof -i :8080
lsof -i :8081
lsof -i :4317

# View service logs
docker-compose logs user-service-typed
docker-compose logs notification-service-typed
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
docker-compose exec user-service-typed ping notification-service-typed
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

---

## Akka Actor Tracing: Classic vs Typed Actors

### Important Discovery: Automatic Actor Instrumentation

During the development of this project, we discovered that **Kamon's automatic Akka instrumentation only works with Classic (untyped) Akka actors, not with Akka Typed actors**.

### The Problem

Initially, the project used Akka Typed actors throughout:

```scala
// Akka Typed - NO automatic tracing
object UserActor {
  sealed trait UserCommand
  case class CreateUser(name: String, replyTo: ActorRef[UserResponse]) extends UserCommand
  
  def apply(): Behavior[UserCommand] = {
    Behaviors.receive { (context, message) =>
      // Actor logic here
      Behaviors.same
    }
  }
}
```

**Result**: Only HTTP traces appeared, no actor spans were generated automatically.

### The Solution

Convert key actors to Classic Akka actors for automatic instrumentation:

```scala
// Classic Akka - FULL automatic tracing
class ClassicUserActor extends Actor with ActorLogging {
  def receive: Receive = {
    case CreateUser(name) =>
      val id = java.util.UUID.randomUUID().toString
      val user = User(id, name)
      log.info(s"Created user: $id, $name")
      sender() ! UserCreated(user)
  }
}
```

**Result**: Rich automatic actor traces with detailed attributes!

### Automatic Trace Details

With Classic actors, Kamon automatically generates spans with:

```
InstrumentationScope akka.actor 2.7.0
Span: ask(CreateUser)
Attributes:
  -> akka.actor.class: com.example.ClassicUserActor
  -> akka.actor.message-class: CreateUser
  -> akka.actor.path: user-system/user/user-actor
  -> akka.system: user-system
  -> component: akka.actor
  -> operation: ask(CreateUser)
  -> parentOperation: /api/users
```

### Configuration Requirements

Ensure Akka instrumentation is enabled in `application.conf`:

```hocon
kamon {
  instrumentation {
    akka {
      enabled = true  # ← Critical setting!
    }
    akka-http {
      enabled = true
    }
  }
}
```

### Hybrid Approach

This project uses a hybrid approach:
- **Classic actors** for key business logic (automatic tracing)
- **Typed actors** for complex workflows (manual tracing when needed)
- **All HTTP** automatically traced regardless of actor type

### Manual Tracing Alternative

If you must use Akka Typed, add manual spans:

```scala
case CreateUser(name, replyTo) =>
  val span = Kamon.spanBuilder("UserActor.CreateUser")
    .tag("actor.name", "UserActor")
    .tag("user.name", name)
    .start()
    
  // Business logic
  val user = User(id, name)
  
  span.tag("user.created.id", id)
  span.finish()
  
  replyTo ! UserCreated(user)
  Behaviors.same
```

### Recommendations

1. **Use Classic Akka actors** for automatic instrumentation when possible
2. **Enable `kamon.instrumentation.akka.enabled = true`** in configuration  
3. **Keep Akka Typed** for complex stateful actors where type safety is critical
4. **Add manual spans** to Akka Typed actors for important business operations
5. **Test your instrumentation** by checking traces in your observability backend

### Dependencies for Actor Tracing

Ensure these dependencies are included:

```scala
"io.kamon" %% "kamon-akka" % "2.7.0",        // Actor instrumentation
"io.kamon" %% "kamon-akka-http" % "2.7.0",    // HTTP instrumentation
"com.typesafe.akka" %% "akka-actor" % "2.8.5" // Classic actors
```

This discovery significantly improves observability by providing automatic, detailed tracing of actor interactions without any manual instrumentation code.

---

## Advanced Akka Features with Automatic Tracing

Beyond basic actor messaging, this project demonstrates several advanced Akka features that are automatically traced by Kamon:

### 🎯 Actor Routers (Load Balancing)

**Feature**: Distribute messages across multiple worker actors for parallel processing.

```scala
// Create router with multiple workers - each route is traced!
private var notificationRouter = {
  val routees = Vector.fill(3) {
    val r = context.actorOf(Props[NotificationWorkerActor]())
    context watch r
    ActorRefRoutee(r)
  }
  Router(RoundRobinRoutingLogic(), routees)
}

// Route message - Kamon traces which worker handled it
notificationRouter.route(ProcessNotification(userId, "user_created"), self)
```

**Automatic Tracing**:
- Each worker instance gets separate traces
- Router selection algorithm is visible in spans
- Load distribution patterns automatically captured

### 🌊 Akka Streams (Reactive Streams)

**Feature**: Process data through composable, backpressure-aware stream pipelines.

```scala
// Stream processing pipeline - each stage is traced!
Source(events)
  .via(validationFlow)    // ← Traced
  .via(enrichmentFlow)    // ← Traced  
  .via(auditFlow)         // ← Traced
  .runWith(Sink.fold(0)((acc, _) => acc + 1))
```

**Automatic Tracing**:
- Stream materialization spans
- Each Flow stage gets individual traces
- Backpressure and throughput traces
- Source and Sink operations traced

**Test Endpoint**: `GET /api/test-streams/{userId}`

### ⚡ Circuit Breaker Pattern

**Feature**: Prevent cascading failures when calling external services.

```scala
private val breaker = CircuitBreaker(
  context.system.scheduler,
  maxFailures = 3,
  callTimeout = 2.seconds,
  resetTimeout = 10.seconds
)

// Circuit breaker calls are automatically traced with state info!
breaker.withCircuitBreaker(simulateExternalCall(userId))
```

**Automatic Tracing**:
- Circuit breaker state changes (CLOSED → OPEN → HALF-OPEN)
- Success/failure rates automatically tracked
- Timeout and error spans with detailed attributes
- Recovery attempts traced

**Test Endpoint**: `GET /api/test-circuit-breaker/{userId}` (30% failure rate for demo)

### 📅 Actor Scheduling

**Feature**: Schedule recurring messages to actors for maintenance tasks.

```scala
// Scheduled messages are automatically traced!
private val cleanupScheduler = context.system.scheduler.scheduleWithFixedDelay(
  initialDelay = 30.seconds,
  delay = 60.seconds, 
  receiver = self,
  message = CleanupUsers
)
```

**Automatic Tracing**:
- Scheduler creation and cancellation
- Each scheduled message delivery traced
- Timing precision and delays captured

### 🏗️ Actor Hierarchies & Supervision

**Feature**: Parent actors supervise child actors with automatic failure recovery.

```scala
// Child actor creation - supervision tree is traced!
val emailActor = context.actorOf(Props[EmailActor](), "email-actor")
val auditActor = context.actorOf(Props[AuditActor](), "audit-actor")

// Child interactions show parent-child relationship in traces
emailActor ! SendWelcomeEmail(userId, userName)
auditActor ! AuditEvent(s"User $userId created", System.currentTimeMillis())
```

**Automatic Tracing**:
- Actor lifecycle events (preStart, postStop)
- Supervision strategies and restarts
- Parent-child message flows clearly traced
- Actor path hierarchies visible in spans

### 🔄 Actor Lifecycle Events

**Feature**: Hook into actor startup and shutdown for resource management.

```scala
override def preStart(): Unit = {
  log.info("UserActor starting up - this lifecycle event is traced!")
}

override def postStop(): Unit = {
  cleanupScheduler.cancel()
  log.info("UserActor shutting down - lifecycle event traced!")
}
```

**Automatic Tracing**:
- Actor creation and termination spans
- Resource initialization and cleanup traced
- System startup and shutdown flows visible

### 🎭 Actor State Management

**Feature**: Immutable state changes tracked automatically.

```scala
// State changes are reflected in traces through message processing
private var users = Map.empty[String, User]

case CreateUser(name) =>
  val newUser = User(id, name)
  users = users + (id -> newUser)  // State change traced via message span
```

**Automatic Tracing**:
- Message processing duration includes state operations
- Actor behavior changes captured in spans
- Memory usage patterns visible in traces

### 🚀 Performance Benefits

**Router Benefits**:
- Parallel processing automatically load-balanced
- Worker failure isolation  
- Horizontal scaling visibility

**Streams Benefits**:
- Backpressure prevents memory overflow
- Composable processing stages
- Built-in error handling and recovery

**Circuit Breaker Benefits**:
- Prevents cascade failures
- Automatic recovery attempts
- Configurable failure thresholds

### 🔍 Trace Correlation Examples

With all these features combined, a single user creation generates traces showing:

1. **HTTP Request** → User Service
2. **Router Message** → Round-robin to notification worker
3. **Child Actor Messages** → Email and audit actors  
4. **Scheduled Messages** → Cleanup operations
5. **Stream Processing** → Event pipeline stages
6. **Circuit Breaker** → External service calls with failure handling
7. **HTTP Response** → Complete user creation flow

All automatically correlated with distributed trace context!

### 🛠️ Testing the Features

```bash
# Test basic actor functionality
curl -X POST "http://localhost:8080/api/users?name=TestUser"

# Test router load balancing  
curl "http://localhost:8080/api/test-classic"

# Test stream processing
curl "http://localhost:8080/api/test-streams/user123"

# Test circuit breaker (will fail ~30% of the time)
curl "http://localhost:8080/api/test-circuit-breaker/user456"
```

### 📊 Observable Patterns

These traces reveal important system patterns:
- **Load distribution** across router workers
- **Stream throughput** and processing latencies  
- **Circuit breaker state** and failure rates
- **Actor lifecycle** and resource usage
- **Message flow** through supervision hierarchies

All without writing a single manual tracing span!

## Future Research: Classic vs Typed Actors

Limited testing has shown that Classic Akka actors work better with some Akka features than Typed actors for automatic tracing purposes. This observation warrants further exploration to understand:

- Which specific Akka features have better instrumentation support with Classic actors
- Performance implications of mixing Classic and Typed actor systems  
- Best practices for hybrid architectures in distributed tracing scenarios
- Evolution of Kamon's instrumentation support for newer Akka Typed features

This project serves as a baseline for such investigations, demonstrating comprehensive automatic tracing with Classic actors across routers, streams, circuit breakers, and supervision hierarchies.

## OpenTelemetry Java Agent vs Kanela Comparison

Testing has shown that **Kanela (Kamon's agent) provides superior Akka-specific tracing** compared to the standard OpenTelemetry Java agent:

### Kanela + Kamon (Current Implementation)
✅ **Deep Akka actor tracing** - Automatic spans for ask/tell patterns, message types, actor paths  
✅ **Router tracing** - Load balancing and worker selection visibility  
✅ **Akka Streams tracing** - Automatic spans for stream stages and backpressure  
✅ **Circuit breaker tracing** - State changes and failure handling  
✅ **Actor lifecycle tracing** - preStart, postStop, supervision events  
✅ **HTTP tracing** - Client and server requests  

### OpenTelemetry Java Agent (Tested in `test-otel-agent` branch)
✅ **HTTP tracing** - Excellent client and server request tracing  
✅ **General JVM tracing** - Database, messaging, popular libraries  
✅ **Simpler setup** - No additional dependencies, pure JVM properties  
❌ **Minimal Akka tracing** - No automatic actor message spans  
❌ **No router/streams tracing** - Missing Akka-specific features  

### Recommendation

**Use Kanela for Akka-heavy applications** where actor message flows are critical to observability. The specialized Akka instrumentation provides insights that generic Java agents cannot match.

The OpenTelemetry Java agent is excellent for traditional Spring Boot or general Java applications, but Akka's actor model benefits from Kamon's domain-specific knowledge.