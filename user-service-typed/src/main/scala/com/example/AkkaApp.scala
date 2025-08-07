package com.example

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import kamon.Kamon

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Random}

case class NotificationRequest(userId: String, message: String, channel: String)
case class NotificationResponse(id: String, status: String, timestamp: Long)
case class ProcessingEvent(userId: String, eventType: String, data: String, timestamp: Long)
case class ValidationResult(event: ProcessingEvent, isValid: Boolean, validationMessage: String)
case class EnrichmentResult(event: ProcessingEvent, enrichedData: String, metadata: Map[String, String])


object JsonFormats {
  import spray.json.DefaultJsonProtocol._
  implicit val userFormat: RootJsonFormat[TypedUserActor.User] = jsonFormat2(TypedUserActor.User)
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

// Typed UserActor - testing if Akka Typed gets automatic instrumentation  
object TypedUserActor extends LazyLogging {
  sealed trait UserCommand
  case class GetUser(id: String, replyTo: ActorRef[UserResponse]) extends UserCommand
  case class CreateUser(name: String, replyTo: ActorRef[UserResponse]) extends UserCommand
  case class UpdateUser(id: String, name: String, replyTo: ActorRef[UserResponse]) extends UserCommand
  case class DeleteUser(id: String, replyTo: ActorRef[UserResponse]) extends UserCommand
  case object CleanupUsers extends UserCommand

  sealed trait UserResponse
  case class User(id: String, name: String) extends UserResponse
  case class UserCreated(user: User) extends UserResponse
  case class UserUpdated(user: User) extends UserResponse
  case object UserDeleted extends UserResponse
  case object UserNotFound extends UserResponse

  def apply(): Behavior[UserCommand] = {
    Behaviors.setup { context =>
      logger.info("TypedUserActor starting up - testing if this lifecycle event is traced!")
      
      // Skip cleanup scheduling for now - focus on basic functionality
      
      userBehavior(Map.empty[String, User])
    }
  }
  
  private def userBehavior(users: Map[String, User]): Behavior[UserCommand] = {
    Behaviors.receive { (context, message) =>
      message match {
        case GetUser(id, replyTo) =>
          users.get(id) match {
            case Some(user) =>
              logger.info(s"TypedUserActor: Found user: $id")
              replyTo ! user
            case None =>
              logger.info(s"TypedUserActor: User not found: $id")
              replyTo ! UserNotFound
          }
          Behaviors.same

        case CreateUser(name, replyTo) =>
          val id = java.util.UUID.randomUUID().toString
          val newUser = User(id, name)
          logger.info(s"TypedUserActor: Created user: $id, $name")
          replyTo ! UserCreated(newUser)
          userBehavior(users + (id -> newUser))

        case UpdateUser(id, name, replyTo) =>
          if (users.contains(id)) {
            val updatedUser = User(id, name)
            logger.info(s"TypedUserActor: Updated user: $id, $name")
            replyTo ! UserUpdated(updatedUser)
            userBehavior(users + (id -> updatedUser))
          } else {
            logger.info(s"TypedUserActor: User not found for update: $id")
            replyTo ! UserNotFound
            Behaviors.same
          }

        case DeleteUser(id, replyTo) =>
          if (users.contains(id)) {
            logger.info(s"TypedUserActor: Deleted user: $id")
            replyTo ! UserDeleted
            userBehavior(users - id)
          } else {
            logger.info(s"TypedUserActor: User not found for delete: $id")
            replyTo ! UserNotFound
            Behaviors.same
          }
          
        case CleanupUsers =>
          val oldUserCount = users.size
          logger.info(s"TypedUserActor: Cleanup completed: $oldUserCount users remain")
          // Keep all users for demo - in real app would filter by timestamp
          Behaviors.same
      }
    }
  }
}




object AkkaApp extends App with LazyLogging {
  import JsonFormats._

  println("=== USER SERVICE TYPED STARTING ===")
  
  // Initialize Kamon
  Kamon.init()
  println(s"Kamon initialized for service: user-service-typed")

  // Create typed actor system for all actors
  implicit val typedSystem: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "user-system-typed")
  implicit val executionContext: ExecutionContext = typedSystem.executionContext
  implicit val timeout: Timeout = 3.seconds
  
  // Create typed user actor
  val userActor = typedSystem.systemActorOf(TypedUserActor(), "user-actor")
  
  // Create processing actors on typed system
  val validationActor = typedSystem.systemActorOf(ValidationActor(), "validation-actor")
  val enrichmentActor = typedSystem.systemActorOf(EnrichmentActor(), "enrichment-actor")
  val processingCoordinator = typedSystem.systemActorOf(ProcessingCoordinator(validationActor, enrichmentActor), "processing-coordinator")

  def sendNotification(userId: String, message: String): Future[NotificationResponse] = {
    val request = NotificationRequest(userId, message, "email")
    val requestJson = request.toJson.compactPrint
    logger.info(s"Sending notification request: $requestJson")

    val httpRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = "http://notification-service-typed:8083/api/notifications",
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
        path("test-streams" / Segment) { userId =>
          get {
            complete {
              processUserEventsWithStreams(userId).map { processedCount =>
                HttpResponse(StatusCodes.OK, entity = s"""{"userId":"$userId","streamProcessedEvents":$processedCount}""")
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
                    import akka.actor.typed.scaladsl.AskPattern._
                    val future: Future[TypedUserActor.UserResponse] = userActor.ask(TypedUserActor.CreateUser(name, _))(timeout, typedSystem.scheduler)
                    future.flatMap {
                      case TypedUserActor.UserCreated(user) =>
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
                      case TypedUserActor.UserNotFound =>
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
                    import akka.actor.typed.scaladsl.AskPattern._
                    val future: Future[TypedUserActor.UserResponse] = userActor.ask(TypedUserActor.GetUser(id, _))(timeout, typedSystem.scheduler)
                    future.map {
                      case user: TypedUserActor.User =>
                        HttpResponse(StatusCodes.OK, entity = user.toJson.compactPrint)
                      case TypedUserActor.UserNotFound =>
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
                      import akka.actor.typed.scaladsl.AskPattern._
                      val future: Future[TypedUserActor.UserResponse] = userActor.ask(TypedUserActor.UpdateUser(id, newName, _))(timeout, typedSystem.scheduler)
                      future.map {
                        case TypedUserActor.UserUpdated(user) =>
                          HttpResponse(StatusCodes.OK, entity = user.toJson.compactPrint)
                        case TypedUserActor.UserNotFound =>
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
                    import akka.actor.typed.scaladsl.AskPattern._
                    val future: Future[TypedUserActor.UserResponse] = userActor.ask(TypedUserActor.DeleteUser(id, _))(timeout, typedSystem.scheduler)
                    future.map {
                      case TypedUserActor.UserDeleted =>
                        HttpResponse(StatusCodes.NoContent)
                      case TypedUserActor.UserNotFound =>
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

  val bindingFuture = Http().newServerAt("0.0.0.0", 8082).bind(route)
  println(s"User Service online at http://localhost:8082/")
  
  // Add shutdown hook for graceful shutdown in Docker
  sys.addShutdownHook {
    typedSystem.terminate()
    println("=== User Service Typed stopped ===")
  }
  
  // Keep the application running (block main thread)
  scala.concurrent.Await.result(typedSystem.whenTerminated, scala.concurrent.duration.Duration.Inf)
}