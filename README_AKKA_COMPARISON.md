# Akka Classic vs Akka Typed: A Comprehensive Comparison

## Overview

Akka Classic (the original actor system) and Akka Typed (introduced in Akka 2.6) represent two different approaches to building actor-based systems. This document covers their key differences, migration challenges, and critically important bytecode injection considerations.

## Core Differences

### Actor Definition and Type Safety

**Akka Classic:**
```scala
import akka.actor.{Actor, ActorRef, ActorSystem, Props}

class UserActor extends Actor {
  def receive = {
    case "hello" => sender() ! "world"
    case msg => println(s"Unknown message: $msg")
  }
}

val system = ActorSystem("MySystem")
val userActor = system.actorOf(Props[UserActor], "user")
userActor ! "hello" // No compile-time type checking
```

**Akka Typed:**
```scala
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

sealed trait UserMessage
case object Hello extends UserMessage
case class Reply(message: String) extends UserMessage

object UserActor {
  def apply(): Behavior[UserMessage] = Behaviors.receive { (context, message) =>
    message match {
      case Hello => 
        context.log.info("Received hello")
        Behaviors.same
      case Reply(msg) =>
        context.log.info(s"Reply: $msg")
        Behaviors.same
    }
  }
}

val system: ActorSystem[UserMessage] = ActorSystem(UserActor(), "MySystem")
system ! Hello // Compile-time type checking
```

### Message Protocol

**Akka Classic:**
- Uses `Any` type for messages
- No compile-time guarantees about message types
- Runtime pattern matching with potential for unhandled cases
- `sender()` implicit reference for replies

**Akka Typed:**
- Strongly typed message protocols
- Compile-time verification of message types
- Explicit `ActorRef[T]` for communication
- No implicit sender - must be passed explicitly

### Actor Lifecycle

**Akka Classic:**
```scala
class MyActor extends Actor {
  override def preStart(): Unit = println("Actor starting")
  override def postStop(): Unit = println("Actor stopping")
  
  def receive = {
    case "stop" => context.stop(self)
  }
}
```

**Akka Typed:**
```scala
object MyActor {
  def apply(): Behavior[String] = 
    Behaviors.setup { context =>
      context.log.info("Actor starting")
      
      Behaviors.receiveMessage[String] {
        case "stop" => Behaviors.stopped
        case msg => 
          context.log.info(s"Received: $msg")
          Behaviors.same
      }.receiveSignal {
        case (context, PostStop) =>
          context.log.info("Actor stopping")
          Behaviors.same
      }
    }
}
```

## Migration Issues and Challenges

### 1. **API Incompatibility**
- Complete rewrite of actor creation patterns
- Different supervision strategies
- Changed testing approaches
- No drop-in replacement possible

### 2. **Ecosystem Fragmentation**
- Many libraries still Classic-only
- Mixed codebases require both systems running
- Third-party integrations may lag behind

### 3. **Learning Curve**
- Functional programming paradigm shift
- Behavior composition concepts
- Different mental model for actor state

### 4. **Performance Considerations**
- Typed has slight overhead for type safety
- Different memory patterns
- Garbage collection implications

## Bytecode Injection: Critical Differences

### Overview
Bytecode injection is crucial for observability tools, APM solutions, and debugging frameworks. The architectural differences between Classic and Typed create significant challenges for instrumentation.

### Akka Classic Bytecode Injection

**Advantages:**
- **Stable Instrumentation Points**: Classic actors have consistent lifecycle methods (`preStart`, `postStop`, `receive`)
- **Predictable Patterns**: Standard inheritance-based structure makes injection points well-defined
- **Mature Tooling Support**: Most APM tools (New Relic, AppDynamics, DataDog) have robust Classic support

**Common Injection Points:**
```scala
// These methods are commonly instrumented
class ActorToInstrument extends Actor {
  override def preStart(): Unit = { /* INJECT HERE */ }
  
  def receive = { /* INJECT MESSAGE PROCESSING HERE */
    case msg => /* INJECT AROUND PATTERN MATCHING */
  }
  
  override def postStop(): Unit = { /* INJECT HERE */ }
}
```

**Instrumentation Libraries Supporting Classic:**
- Kamon (excellent support)
- Lightbend Telemetry
- New Relic
- AppDynamics
- Custom AspectJ weaving

### Akka Typed Bytecode Injection Challenges

**Major Issues:**

1. **No Standard Actor Class**: Typed uses behavior functions, not classes
2. **Dynamic Behavior Switching**: Behaviors can change at runtime, making static instrumentation complex
3. **Functional Composition**: Behavior combinators create nested call stacks difficult to instrument
4. **Limited Instrumentation Points**: Fewer consistent lifecycle hooks

**Example of Instrumentation Difficulty:**
```scala
// Hard to instrument - no class inheritance
def dynamicBehavior(state: Int): Behavior[Command] = {
  Behaviors.receive { (context, message) =>
    message match {
      case Increment => dynamicBehavior(state + 1) // Behavior switch!
      case GetState(replyTo) => 
        replyTo ! State(state)
        Behaviors.same
    }
  }
}
```

**Current Support Status (as of 2024):**

| Tool | Classic Support | Typed Support | Notes |
|------|----------------|---------------|-------|
| Kamon | Excellent | Limited | Basic message tracing only |
| OpenTelemetry Java Agent | Good | Poor | Limited actor-specific instrumentation |
| New Relic | Excellent | Experimental | May miss behavior switches |
| AppDynamics | Good | Poor | Minimal typed support |
| DataDog | Good | Basic | Manual instrumentation required |
| Lightbend Telemetry | Excellent | Good | Best typed support available (commercial license) |

### Specific Bytecode Injection Patterns

**Classic - AspectJ Example:**
```aspectj
@Aspect
public class ClassicActorInstrumentation {
    @Around("execution(* akka.actor.Actor.receive(..))")
    public Object instrumentReceive(ProceedingJoinPoint pjp) throws Throwable {
        // Start span
        Span span = tracer.nextSpan().name("actor-receive").start();
        try {
            return pjp.proceed();
        } finally {
            span.end();
        }
    }
}
```

**Typed - Complex Instrumentation:**
```scala
// Must wrap behavior creation
object InstrumentedBehaviors {
  def instrument[T](behavior: Behavior[T]): Behavior[T] = {
    Behaviors.intercept(() => new BehaviorInterceptor[Any, T] {
      override def aroundReceive(
          ctx: TypedActorContext[Any], 
          msg: T, 
          target: BehaviorInterceptor.ReceiveTarget[T]
      ): Behavior[T] = {
        val span = startSpan("typed-actor-receive")
        try {
          target(ctx, msg)
        } finally {
          span.finish()
        }
      }
    })(behavior)
  }
}
```

### Instrumentation Recommendations

**For New Projects:**
- Use Typed for type safety benefits
- Accept limited instrumentation initially
- Plan for manual tracing where needed
- Consider Lightbend Telemetry for best Typed support

**For Existing Classic Projects:**
- Migration may break existing instrumentation
- Test APM tools thoroughly after migration
- Consider gradual migration with both systems
- Keep Classic actors for critical instrumented paths

**For Mixed Environments:**
- Run both Classic and Typed systems
- Bridge between them carefully
- Instrument at system boundaries
- Use consistent tracing correlation IDs

### Workarounds for Typed Instrumentation

1. **Manual Instrumentation**: Explicitly add tracing in behaviors
2. **Behavior Decorators**: Wrap behaviors with instrumentation logic  
3. **Message Interceptors**: Use Typed's built-in interception
4. **System-Level Tracing**: Focus on actor system boundaries
5. **Custom Receptionist**: Instrument service discovery

## Conclusion

While Akka Typed provides superior type safety and modern functional patterns, bytecode injection and observability remain significant challenges. Classic's mature instrumentation ecosystem makes it still viable for production systems requiring deep observability.

**Decision Matrix:**

| Priority | Recommendation |
|----------|----------------|
| Type Safety > Observability | Choose Typed |
| Deep APM Integration Required | Stay with Classic |
| Greenfield + Learning Team | Choose Typed |
| Production + Established Monitoring | Consider staying Classic |
| Migration Timeline > 2 years | Plan gradual Typed adoption |

The bytecode injection landscape for Typed will improve, but currently requires significantly more manual effort and tooling investment compared to Classic's mature ecosystem.