package com.example

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import kamon.Kamon
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Random}
import akka.pattern.pipe

case class NotificationRequest(userId: String, message: String, channel: String)
case class NotificationResponse(id: String, status: String, timestamp: Long)
case class ProcessingEvent(userId: String, eventType: String, data: String, timestamp: Long)
case class ValidationResult(event: ProcessingEvent, isValid: Boolean, validationMessage: String)
case class EnrichmentResult(event: ProcessingEvent, enrichedData: String, metadata: Map[String, String])

// Classic actor for testing automatic Kamon instrumentation
class TestClassicActor extends Actor with ActorLogging {
  def receive: Receive = {
    case msg: String =>
      log.info(s"TestClassicActor received: $msg")
      Thread.sleep(50) // Simulate work
      sender() ! s"Processed: $msg"
  }
}

object JsonFormats {
  import spray.json.DefaultJsonProtocol._
  implicit val userFormat: RootJsonFormat[ClassicUserActor.User] = jsonFormat2(ClassicUserActor.User)
  implicit val notificationRequestFormat: RootJsonFormat[NotificationRequest] = jsonFormat3(NotificationRequest)
  implicit val notificationResponseFormat: RootJsonFormat[NotificationResponse] = jsonFormat3(NotificationResponse)
  implicit val processingEventFormat: RootJsonFormat[ProcessingEvent] = jsonFormat4(ProcessingEvent)
}

// ValidationActor handles event validation
object ValidationActor extends LazyLogging {
  sealed trait ValidationCommand
  final case class ValidateEvent(event: ProcessingEvent, replyTo: ActorRef[ValidationResult]) extends ValidationCommand

  def apply(): Behavior[ValidationCommand] = {
    Behaviors.receive { (context, message) =>
      message match {
        case ValidateEvent(event, replyTo) =>
          
          logger.info(s"ValidationActor: Validating event ${event.eventType} for user ${event.userId}")
          
          // Simulate validation processing time
          Thread.sleep(Random.nextInt(50) + 10)
          
          val isValid = event.eventType.nonEmpty && event.data.nonEmpty
          val validationMessage = if (isValid) "Event is valid" else "Event validation failed"
          
          logger.info(s"ValidationActor: Event ${event.eventType} validation result: $isValid")
          
          replyTo ! ValidationResult(event, isValid, validationMessage)
          Behaviors.same
      }
    }
  }
}

// EnrichmentActor handles data enrichment
object EnrichmentActor extends LazyLogging {
  sealed trait EnrichmentCommand
  final case class EnrichEvent(event: ProcessingEvent, replyTo: ActorRef[EnrichmentResult]) extends EnrichmentCommand

  def apply(): Behavior[EnrichmentCommand] = {
    Behaviors.receive { (context, message) =>
      message match {
        case EnrichEvent(event, replyTo) =>
          
          logger.info(s"EnrichmentActor: Enriching event ${event.eventType} for user ${event.userId}")
          
          // Simulate enrichment processing time
          Thread.sleep(Random.nextInt(100) + 20)
          
          val enrichedData = event.data.toUpperCase + s"_ENRICHED_${System.currentTimeMillis()}"
          val metadata = Map(
            "enriched_by" -> "user-service",
            "processing_timestamp" -> java.time.Instant.now().toString,
            "enrichment_version" -> "1.0"
          )
          
          logger.info(s"EnrichmentActor: Event ${event.eventType} enriched successfully")
          
          replyTo ! EnrichmentResult(event, enrichedData, metadata)
          Behaviors.same
      }
    }
  }
}

// ProcessingCoordinator orchestrates the validation and enrichment flow
object ProcessingCoordinator extends LazyLogging {
  sealed trait ProcessingCommand
  final case class ProcessUserEvents(userId: String, events: List[ProcessingEvent], replyTo: ActorRef[ProcessingComplete]) extends ProcessingCommand
  final case class ProcessingComplete(userId: String, processedCount: Int, results: List[EnrichmentResult]) extends ProcessingCommand

  private final case class ValidationCompleted(validationResults: List[ValidationResult], replyTo: ActorRef[ProcessingComplete]) extends ProcessingCommand
  private final case class EnrichmentCompleted(enrichmentResults: List[EnrichmentResult], replyTo: ActorRef[ProcessingComplete]) extends ProcessingCommand

  def apply(validationActor: ActorRef[ValidationActor.ValidationCommand], 
           enrichmentActor: ActorRef[EnrichmentActor.EnrichmentCommand]): Behavior[ProcessingCommand] = {
    Behaviors.receive { (context, message) =>
      message match {
        case ProcessUserEvents(userId, events, replyTo) =>
          logger.info(s"ProcessingCoordinator: Starting processing of ${events.length} events for user $userId")
          
          // Start validation for all events
          import akka.actor.typed.scaladsl.AskPattern._
          implicit val timeout: Timeout = 5.seconds
          implicit val ec: ExecutionContext = context.executionContext
          implicit val scheduler: akka.actor.typed.Scheduler = context.system.scheduler
          
          val validationFutures: List[Future[ValidationResult]] = events.map { event =>
            validationActor.ask(ValidationActor.ValidateEvent(event, _))
          }
          
          Future.sequence(validationFutures).foreach { validationResults =>
            context.self ! ValidationCompleted(validationResults, replyTo)
          }
          
          Behaviors.same
          
        case ValidationCompleted(validationResults, replyTo) =>
          logger.info(s"ProcessingCoordinator: Validation completed for ${validationResults.length} events")
          
          // Filter valid events and start enrichment
          val validEvents = validationResults.filter(_.isValid).map(_.event)
          logger.info(s"ProcessingCoordinator: ${validEvents.length} events passed validation")
          
          if (validEvents.nonEmpty) {
            import akka.actor.typed.scaladsl.AskPattern._
            implicit val timeout: Timeout = 5.seconds
            implicit val ec: ExecutionContext = context.executionContext
            implicit val scheduler: akka.actor.typed.Scheduler = context.system.scheduler
            
            val enrichmentFutures: List[Future[EnrichmentResult]] = validEvents.map { event =>
              enrichmentActor.ask(EnrichmentActor.EnrichEvent(event, _))
            }
            
            Future.sequence(enrichmentFutures).foreach { enrichmentResults =>
              context.self ! EnrichmentCompleted(enrichmentResults, replyTo)
            }
          } else {
            // No valid events, return empty results
            replyTo ! ProcessingComplete("", 0, List.empty)
          }
          
          Behaviors.same
          
        case EnrichmentCompleted(enrichmentResults, replyTo) =>
          logger.info(s"ProcessingCoordinator: Enrichment completed for ${enrichmentResults.length} events")
          val userId = if (enrichmentResults.nonEmpty) enrichmentResults.head.event.userId else ""
          replyTo ! ProcessingComplete(userId, enrichmentResults.length, enrichmentResults)
          Behaviors.same
      }
    }
  }
}

// Classic UserActor with supervision for automatic instrumentation
class ClassicUserActor extends Actor with ActorLogging {
  import ClassicUserActor._
  import context.dispatcher
  
  private var users = Map.empty[String, User]
  
  // Create child actors for different responsibilities - automatically traced!
  val emailActor = context.actorOf(Props[EmailActor](), "email-actor")
  val auditActor = context.actorOf(Props[AuditActor](), "audit-actor")
  
  // Create router for load balancing - router messages are traced!
  private var notificationRouter = {
    val routees = Vector.fill(3) {
      val r = context.actorOf(Props[NotificationWorkerActor]())
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  // Demonstrate actor scheduling - automatically traced!
  private val cleanupScheduler = context.system.scheduler.scheduleWithFixedDelay(
    initialDelay = 30.seconds,
    delay = 60.seconds,
    receiver = self,
    message = CleanupUsers
  )

  override def preStart(): Unit = {
    log.info("UserActor starting up - this lifecycle event is traced!")
  }

  override def postStop(): Unit = {
    cleanupScheduler.cancel()
    log.info("UserActor shutting down - lifecycle event traced!")
  }

  def receive: Receive = {
    case GetUser(id) =>
      users.get(id) match {
        case Some(user) =>
          log.info(s"Found user: $id")
          // Audit access - child actor interaction traced!
          auditActor ! AuditEvent(s"User $id accessed", System.currentTimeMillis())
          sender() ! user
        case None =>
          log.info(s"User not found: $id") 
          sender() ! UserNotFound
      }

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

    case UpdateUser(id, name) =>
      if (users.contains(id)) {
        val updatedUser = User(id, name)
        users = users + (id -> updatedUser)
        log.info(s"Updated user: $id, $name")
        auditActor ! AuditEvent(s"User $id updated", System.currentTimeMillis())
        sender() ! UserUpdated(updatedUser)
      } else {
        log.info(s"User not found for update: $id")
        sender() ! UserNotFound
      }

    case DeleteUser(id) =>
      if (users.contains(id)) {
        users = users - id
        log.info(s"Deleted user: $id")
        auditActor ! AuditEvent(s"User $id deleted", System.currentTimeMillis())
        sender() ! UserDeleted
      } else {
        log.info(s"User not found for delete: $id")
        sender() ! UserNotFound
      }
      
    case CleanupUsers =>
      val oldUserCount = users.size
      // Simulate cleanup logic - this scheduled message is traced!
      users = users.filter { case (_, user) => 
        // Keep all users for demo - in real app would filter by timestamp
        true 
      }
      log.info(s"Cleanup completed: $oldUserCount users remain")
  }
}

// Child actor for email operations - automatically traced!
class EmailActor extends Actor with ActorLogging {
  def receive: Receive = {
    case SendWelcomeEmail(userId, userName) =>
      // Simulate async email sending
      import context.dispatcher
      Future {
        Thread.sleep(50) // Simulate email service call
        log.info(s"Welcome email sent to user $userId ($userName)")
        EmailSent(userId, "welcome")
      }.pipeTo(sender())
  }
}

// Child actor for audit logging - automatically traced!
class AuditActor extends Actor with ActorLogging {
  def receive: Receive = {
    case AuditEvent(event, timestamp) =>
      // Simulate writing to audit log
      log.info(s"AUDIT: $event at $timestamp")
      // In real app, would persist to database
      sender() ! AuditLogged
  }
}

// Router worker actor - each instance is traced separately!
class NotificationWorkerActor extends Actor with ActorLogging {
  def receive: Receive = {
    case ProcessNotification(userId, eventType) =>
      // Simulate notification processing with different latencies per worker
      val processingTime = Random.nextInt(100) + 50
      Thread.sleep(processingTime)
      log.info(s"Worker ${self.path.name} processed notification for user $userId (${eventType}) in ${processingTime}ms")
      sender() ! NotificationProcessed(userId, eventType, processingTime)
  }
}

// Circuit breaker actor - automatically traced with failure handling!
class CircuitBreakerActor extends Actor with ActorLogging {
  import akka.pattern.CircuitBreaker
  import context.dispatcher

  private val breaker = CircuitBreaker(
    context.system.scheduler,
    maxFailures = 3,
    callTimeout = 2.seconds,
    resetTimeout = 10.seconds
  ).onOpen(log.info("Circuit breaker opened!"))
   .onClose(log.info("Circuit breaker closed!"))
   .onHalfOpen(log.info("Circuit breaker half-open"))

  def receive: Receive = {
    case CallExternalService(userId) =>
      val originalSender = sender()
      breaker.withCircuitBreaker(simulateExternalCall(userId))
        .recover {
          case _: java.util.concurrent.TimeoutException =>
            log.warning(s"External service timeout for user $userId")
            ExternalServiceResult(userId, "timeout", success = false)
          case ex =>
            log.error(s"External service error for user $userId: ${ex.getMessage}")
            ExternalServiceResult(userId, "error", success = false)
        }
        .pipeTo(originalSender)

  }

  private def simulateExternalCall(userId: String): Future[ExternalServiceResult] = {
    Future {
      // Randomly fail 30% of the time to demonstrate circuit breaker
      if (Random.nextDouble() < 0.3) {
        throw new RuntimeException("External service failed")
      }
      Thread.sleep(Random.nextInt(100) + 50)
      ExternalServiceResult(userId, "success", success = true)
    }
  }
}

object ClassicUserActor {
  // Commands
  case class GetUser(id: String)
  case class CreateUser(name: String)
  case class UpdateUser(id: String, name: String)
  case class DeleteUser(id: String)
  case object CleanupUsers // Scheduled message

  // Responses
  case class User(id: String, name: String)
  case class UserCreated(user: User)
  case object UserNotFound
  case class UserUpdated(user: User)
  case object UserDeleted
  case object UserOperationFailed
}

// Email actor messages
case class SendWelcomeEmail(userId: String, userName: String)
case class EmailSent(userId: String, emailType: String)

// Audit actor messages  
case class AuditEvent(event: String, timestamp: Long)
case object AuditLogged

// Router worker messages
case class ProcessNotification(userId: String, eventType: String)
case class NotificationProcessed(userId: String, eventType: String, processingTimeMs: Int)

// Circuit breaker messages
case class CallExternalService(userId: String)
case class ExternalServiceResult(userId: String, result: String, success: Boolean)

object AkkaApp extends App with LazyLogging {
  import JsonFormats._

  println("=== USER SERVICE STARTING ===")

  Kamon.init()
  println("=== KAMON INITIALIZED ===")

  // Create classic actor system for the main user actor
  implicit val classicSystem: akka.actor.ActorSystem = akka.actor.ActorSystem("user-system")
  implicit val executionContext: ExecutionContext = classicSystem.dispatcher
  implicit val timeout: Timeout = 3.seconds
  
  // Create classic user actor
  val userActor = classicSystem.actorOf(Props[ClassicUserActor](), "user-actor")

  // Also create a typed system for the other actors  
  val typedSystem: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "typed-system")
  
  // Create processing actors on typed system
  val validationActor = typedSystem.systemActorOf(ValidationActor(), "validation-actor")
  val enrichmentActor = typedSystem.systemActorOf(EnrichmentActor(), "enrichment-actor")
  val processingCoordinator = typedSystem.systemActorOf(ProcessingCoordinator(validationActor, enrichmentActor), "processing-coordinator")
  
  // Create test classic actor
  val testClassicActor = classicSystem.actorOf(Props[TestClassicActor](), "test-classic-actor")
  
  // Create circuit breaker actor for resilience patterns
  val circuitBreakerActor = classicSystem.actorOf(Props[CircuitBreakerActor](), "circuit-breaker")

  def sendNotification(userId: String, message: String): Future[NotificationResponse] = {
    val request = NotificationRequest(userId, message, "email")
    val requestJson = request.toJson.compactPrint
    logger.info(s"Sending notification request: $requestJson")

    val httpRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = "http://notification-service:8081/api/notifications",
      entity = HttpEntity(ContentTypes.`application/json`, requestJson)
    )
    logger.info(s"Making HTTP request to: ${httpRequest.uri}")

    Http().singleRequest(httpRequest).flatMap { response =>
      logger.info(s"Received response with status: ${response.status}")
      response.entity.dataBytes.runFold(new StringBuilder)(_ append _.utf8String).flatMap { body =>
        logger.info(s"Response body: $body")
        if (response.status.isSuccess()) {
          val notificationResponse = body.toString.parseJson.convertTo[NotificationResponse]
          Future.successful(notificationResponse)
        } else {
          Future.failed(new RuntimeException(s"Notification request failed with status ${response.status}"))
        }
      }
    }.recover { case ex =>
      logger.error("Failed to send notification", ex)
      throw ex
    }
  }

  def generateSampleEvents(userId: String): List[ProcessingEvent] = {
    List(
      ProcessingEvent(userId, "welcome", s"welcome-data-for-$userId", System.currentTimeMillis()),
      ProcessingEvent(userId, "profile_setup", s"profile-data-for-$userId", System.currentTimeMillis()),
      ProcessingEvent(userId, "preferences", s"preference-data-for-$userId", System.currentTimeMillis())
    )
  }

  def processUserEventsWithActors(userId: String): Future[ProcessingCoordinator.ProcessingComplete] = {
    logger.info(s"Starting actor-based processing for user: $userId")
    val events = generateSampleEvents(userId)
    
    import akka.actor.typed.scaladsl.AskPattern._
    implicit val scheduler: akka.actor.typed.Scheduler = typedSystem.scheduler
    processingCoordinator.ask(ProcessingCoordinator.ProcessUserEvents(userId, events, _))(timeout, scheduler)
  }

  // Akka Streams processing - automatically traced!
  def processUserEventsWithStreams(userId: String): Future[Int] = {
    logger.info(s"Starting stream-based processing for user: $userId")
    val events = generateSampleEvents(userId)
    
    Source(events)
      .via(validationFlow)
      .via(enrichmentFlow)
      .via(auditFlow)
      .runWith(Sink.fold(0)((acc, _) => acc + 1))
  }

  // Akka Streams flows - each stage automatically traced!
  val validationFlow: Flow[ProcessingEvent, ProcessingEvent, NotUsed] = 
    Flow[ProcessingEvent]
      .map { event =>
        logger.info(s"Stream: Validating event ${event.eventType}")
        Thread.sleep(Random.nextInt(20) + 5) // Simulate validation
        event
      }
      .filter(_.data.nonEmpty) // Filter invalid events

  val enrichmentFlow: Flow[ProcessingEvent, ProcessingEvent, NotUsed] =
    Flow[ProcessingEvent]
      .map { event =>
        logger.info(s"Stream: Enriching event ${event.eventType}")
        Thread.sleep(Random.nextInt(30) + 10) // Simulate enrichment
        event.copy(data = event.data.toUpperCase + "_ENRICHED")
      }

  val auditFlow: Flow[ProcessingEvent, ProcessingEvent, NotUsed] =
    Flow[ProcessingEvent]
      .map { event =>
        logger.info(s"Stream: Auditing event ${event.eventType}")
        // In real app, would persist to audit log
        event
      }

  val route =
    pathPrefix("api") {
      concat(
        path("test-classic") {
          get {
            complete {
              implicit val askTimeout: akka.util.Timeout = akka.util.Timeout(3.seconds)
              val future = (testClassicActor ? "test-message")(askTimeout).mapTo[String]
              future.map(result => HttpResponse(StatusCodes.OK, entity = s"""{"result":"$result"}"""))
            }
          }
        },
        path("test-streams" / Segment) { userId =>
          get {
            complete {
              processUserEventsWithStreams(userId).map { processedCount =>
                HttpResponse(StatusCodes.OK, entity = s"""{"userId":"$userId","streamProcessedEvents":$processedCount}""")
              }
            }
          }
        },
        path("test-circuit-breaker" / Segment) { userId =>
          get {
            complete {
              import akka.pattern.ask
              val future = (circuitBreakerActor ? CallExternalService(userId))(3.seconds).mapTo[ExternalServiceResult]
              future.map { result =>
                HttpResponse(StatusCodes.OK, entity = s"""{"userId":"${result.userId}","result":"${result.result}","success":${result.success}}""")
              }
            }
          }
        },
        pathPrefix("users") {
          concat(
            post {
              parameter("name") { name =>
                extractRequest { httpRequest =>
                  logger.info(s"POST request to create user: $name")

                  complete {
                    val future: Future[Any] = (userActor ? ClassicUserActor.CreateUser(name)).mapTo[ClassicUserActor.UserCreated]
                    future.flatMap {
                      case ClassicUserActor.UserCreated(user) =>
                        logger.info(s"User created: ${user.id}")

                        // Process events using actors instead of streams
                        val processingFuture = processUserEventsWithActors(user.id)
                        val notificationFuture = sendNotification(user.id, s"Welcome, ${user.name}!")

                        for {
                          processingResult <- processingFuture
                          notificationResponse <- notificationFuture
                        } yield {
                          val responseText = s"""{"user":${user.toJson.compactPrint},"onboarding":{"notification_sent":true,"events_processed":${processingResult.processedCount},"notification_id":"${notificationResponse.id}"}}"""
                          HttpResponse(StatusCodes.Created, entity = responseText)
                        }
                      case ClassicUserActor.UserOperationFailed =>
                        Future.successful(HttpResponse(StatusCodes.InternalServerError, entity = "User creation failed"))
                      case _ =>
                        Future.successful(HttpResponse(StatusCodes.InternalServerError, entity = "Unexpected response"))
                    }.recover { case ex =>
                      HttpResponse(StatusCodes.InternalServerError, entity = s"Error creating user: ${ex.getMessage}")
                    }
                  }
                }
              }
            },
            get {
              path(Segment) { id =>
                extractRequest { httpRequest =>
                  logger.info(s"GET request for user: $id")

                  complete {
                    val future: Future[Any] = userActor ? ClassicUserActor.GetUser(id)
                    future.map {
                      case user: ClassicUserActor.User =>
                        HttpResponse(StatusCodes.OK, entity = user.toJson.compactPrint)
                      case ClassicUserActor.UserNotFound =>
                        HttpResponse(StatusCodes.NotFound, entity = "User not found")
                      case _ =>
                        HttpResponse(StatusCodes.InternalServerError, entity = "Unexpected response")
                    }.recover { case ex =>
                      HttpResponse(StatusCodes.InternalServerError, entity = s"Error retrieving user: ${ex.getMessage}")
                    }
                  }
                }
              }
            },
            put {
              path(Segment) { id =>
                parameter("name") { newName =>
                  extractRequest { httpRequest =>
                    logger.info(s"PUT request to update user $id with name: $newName")

                    complete {
                      val future: Future[Any] = userActor ? ClassicUserActor.UpdateUser(id, newName)
                      future.map {
                        case ClassicUserActor.UserUpdated(user) =>
                          HttpResponse(StatusCodes.OK, entity = user.toJson.compactPrint)
                        case ClassicUserActor.UserNotFound =>
                          HttpResponse(StatusCodes.NotFound, entity = "User not found")
                        case _ =>
                          HttpResponse(StatusCodes.InternalServerError, entity = "Unexpected response")
                      }.recover { case ex =>
                        HttpResponse(StatusCodes.InternalServerError, entity = s"Error updating user: ${ex.getMessage}")
                      }
                    }
                  }
                }
              }
            },
            delete {
              path(Segment) { id =>
                extractRequest { httpRequest =>
                  logger.info(s"DELETE request for user: $id")

                  complete {
                    val future: Future[Any] = userActor ? ClassicUserActor.DeleteUser(id)
                    future.map {
                      case ClassicUserActor.UserDeleted =>
                        HttpResponse(StatusCodes.NoContent)
                      case ClassicUserActor.UserNotFound =>
                        HttpResponse(StatusCodes.NotFound, entity = "User not found")
                      case _ =>
                        HttpResponse(StatusCodes.InternalServerError, entity = "Unexpected response")
                    }.recover { case ex =>
                      HttpResponse(StatusCodes.InternalServerError, entity = s"Error deleting user: ${ex.getMessage}")
                    }
                  }
                }
              }
            }
          )
        }
      )
    }

  val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(route)
  println(s"User Service online at http://localhost:8080/")
  
  // Add shutdown hook for graceful shutdown in Docker
  sys.addShutdownHook {
    println("=== Stopping Kamon ===")
    Kamon.stop()
    typedSystem.terminate()
    classicSystem.terminate()
    println("=== User Service stopped ===")
  }
  
  // Keep the application running (block main thread)
  scala.concurrent.Await.result(classicSystem.whenTerminated, scala.concurrent.duration.Duration.Inf)
}