package com.example

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.NotUsed
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import kamon.Kamon

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
class ValidationActor extends Actor with ActorLogging {
  def receive: Receive = {
    case event: ProcessingEvent =>
      log.info(s"Validating event: ${event.eventType} for user ${event.userId}")
      Thread.sleep(30) // Simulate validation work
      
      val isValid = event.eventType != "invalid"
      val result = ValidationResult(event, isValid, if (isValid) "Valid" else "Invalid event type")
      
      log.info(s"Validation result: ${result.validationMessage}")
      sender() ! result
  }
}

// EnrichmentActor handles event enrichment
class EnrichmentActor extends Actor with ActorLogging {
  def receive: Receive = {
    case result: ValidationResult if result.isValid =>
      log.info(s"Enriching valid event: ${result.event.eventType}")
      Thread.sleep(40) // Simulate enrichment work
      
      val enrichedData = s"enriched_${result.event.data}"
      val metadata = Map(
        "processed_at" -> System.currentTimeMillis().toString,
        "enrichment_version" -> "1.0"
      )
      
      val enrichmentResult = EnrichmentResult(result.event, enrichedData, metadata)
      log.info(s"Event enriched with metadata: ${metadata.keys.mkString(", ")}")
      sender() ! enrichmentResult
      
    case result: ValidationResult =>
      log.warning(s"Skipping enrichment for invalid event: ${result.event.eventType}")
      sender() ! result // Pass through invalid events
  }
}

// ProcessingCoordinator orchestrates the validation and enrichment pipeline
class ProcessingCoordinator(validationActor: ActorRef, enrichmentActor: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher
  implicit val timeout: Timeout = 5.seconds
  
  def receive: Receive = {
    case events: List[ProcessingEvent] =>
      log.info(s"Processing batch of ${events.length} events")
      val originalSender = sender()
      
      val futures: List[Future[Any]] = events.map { event =>
        for {
          validationResult <- (validationActor ? event).mapTo[ValidationResult]
          finalResult <- if (validationResult.isValid) {
            (enrichmentActor ? validationResult).mapTo[EnrichmentResult]
          } else {
            Future.successful(validationResult)
          }
        } yield finalResult
      }
      
      Future.sequence(futures).pipeTo(originalSender)
  }
}

object ProcessingCoordinator {
  def props(validationActor: ActorRef, enrichmentActor: ActorRef): Props = 
    Props(new ProcessingCoordinator(validationActor, enrichmentActor))
}

// Circuit breaker pattern actor for resilience
class CircuitBreakerActor extends Actor with ActorLogging {
  private var failureCount = 0
  private val maxFailures = 3
  private var isOpen = false
  private var lastFailureTime = 0L
  private val timeout = 10000L // 10 seconds
  
  def receive: Receive = {
    case "test" =>
      if (isOpen && (System.currentTimeMillis() - lastFailureTime) > timeout) {
        log.info("Circuit breaker timeout expired, attempting to close")
        isOpen = false
        failureCount = 0
      }
      
      if (isOpen) {
        log.warning("Circuit breaker is OPEN - rejecting request")
        sender() ! "CIRCUIT_OPEN"
      } else {
        // Simulate some operation that might fail
        if (Random.nextDouble() < 0.3) { // 30% failure rate
          failureCount += 1
          lastFailureTime = System.currentTimeMillis()
          
          if (failureCount >= maxFailures) {
            isOpen = true
            log.error(s"Circuit breaker OPENED after $failureCount failures")
          }
          
          sender() ! "FAILURE"
        } else {
          failureCount = 0 // Reset on success
          sender() ! "SUCCESS"
        }
      }
  }
}

// Main user actor using classic Akka actors
object ClassicUserActor {
  case class User(id: String, name: String)
  
  // Messages
  case class CreateUser(name: String)
  case class GetUser(id: String)
  case class UpdateUser(id: String, newName: String)
  case class DeleteUser(id: String)
  case class ListUsers()
  
  // Responses
  case class UserCreated(user: User)
  case class UserFound(user: User)
  case class UserUpdated(user: User)
  case object UserDeleted
  case object UserNotFound
  case class UserList(users: List[User])
  
  def props(): Props = Props(new ClassicUserActor())
}

class ClassicUserActor extends Actor with ActorLogging {
  import ClassicUserActor._
  
  private var users = Map.empty[String, User]
  
  def receive: Receive = {
    case CreateUser(name) =>
      val userId = java.util.UUID.randomUUID().toString
      val user = User(userId, name)
      users = users + (userId -> user)
      
      log.info(s"Created user: $user")
      sender() ! UserCreated(user)
      
    case GetUser(id) =>
      users.get(id) match {
        case Some(user) => 
          log.info(s"Found user: $user")
          sender() ! UserFound(user)
        case None => 
          log.warning(s"User not found: $id")
          sender() ! UserNotFound
      }
      
    case UpdateUser(id, newName) =>
      users.get(id) match {
        case Some(user) =>
          val updatedUser = user.copy(name = newName)
          users = users + (id -> updatedUser)
          log.info(s"Updated user: $updatedUser")
          sender() ! UserUpdated(updatedUser)
        case None =>
          log.warning(s"Cannot update - user not found: $id")
          sender() ! UserNotFound
      }
      
    case DeleteUser(id) =>
      users.get(id) match {
        case Some(_) =>
          users = users - id
          log.info(s"Deleted user: $id")
          sender() ! UserDeleted
        case None =>
          log.warning(s"Cannot delete - user not found: $id")
          sender() ! UserNotFound
      }
      
    case ListUsers() =>
      val userList = users.values.toList
      log.info(s"Retrieved ${userList.length} users")
      sender() ! UserList(userList)
  }
}

object AkkaApp extends App with LazyLogging {
  import JsonFormats._

  println("=== USER SERVICE STARTING ===")
  Kamon.init()
  println("=== KAMON INITIALIZED ===")

  // Create classic actor system
  implicit val system: ActorSystem = ActorSystem("user-system")
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = 3.seconds
  
  // Create classic actors
  val userActor = system.actorOf(ClassicUserActor.props(), "user-actor")
  val validationActor = system.actorOf(Props[ValidationActor](), "validation-actor")
  val enrichmentActor = system.actorOf(Props[EnrichmentActor](), "enrichment-actor")
  val processingCoordinator = system.actorOf(ProcessingCoordinator.props(validationActor, enrichmentActor), "processing-coordinator")
  val testClassicActor = system.actorOf(Props[TestClassicActor](), "test-classic-actor")
  val circuitBreakerActor = system.actorOf(Props[CircuitBreakerActor](), "circuit-breaker")

  def sendNotification(userId: String, message: String): Future[NotificationResponse] = {
    val request = NotificationRequest(userId, message, "email")
    val requestJson = request.toJson.compactPrint
    logger.info(s"Sending notification request: $requestJson")

    val httpRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = "http://notification-classic:8081/api/notifications",
      entity = HttpEntity(ContentTypes.`application/json`, requestJson)
    )
    logger.info(s"Making HTTP request to: ${httpRequest.uri}")

    Http().singleRequest(httpRequest).flatMap { response =>
      logger.info(s"Received response with status: ${response.status}")
      response.entity.dataBytes.runFold(new StringBuilder)(_ append _.utf8String).flatMap { body =>
        logger.info(s"Response body: $body")
        try {
          val notificationResponse = body.toString.parseJson.convertTo[NotificationResponse]
          Future.successful(notificationResponse)
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to parse notification response: ${ex.getMessage}")
            Future.failed(ex)
        }
      }
    }.recover {
      case ex: Exception =>
        logger.error(s"Notification request failed: ${ex.getMessage}")
        NotificationResponse("error", "failed", System.currentTimeMillis())
    }
  }

  def processUserEvents(userId: String): Future[List[Any]] = {
    val events = List(
      ProcessingEvent(userId, "UserCreatedEvent", s"user_$userId", System.currentTimeMillis()),
      ProcessingEvent(userId, "WelcomeEmailEvent", s"welcome_$userId", System.currentTimeMillis()),
      ProcessingEvent(userId, "OnboardingEvent", s"onboarding_$userId", System.currentTimeMillis())
    )
    
    logger.info(s"Processing ${events.length} events for user $userId")
    (processingCoordinator ? events).mapTo[List[Any]]
  }

  val route =
    pathPrefix("api" / "users") {
      concat(
        pathEndOrSingleSlash {
          concat(
            get {
              extractRequest { httpRequest =>
                logger.info("GET request to list all users")

                complete {
                  val future: Future[Any] = userActor ? ClassicUserActor.ListUsers()
                  future.map {
                    case ClassicUserActor.UserList(users) =>
                      HttpResponse(StatusCodes.OK, entity = users.toJson.compactPrint)
                    case _ =>
                      HttpResponse(StatusCodes.InternalServerError, entity = "Unexpected response")
                  }.recover { case ex =>
                    HttpResponse(StatusCodes.InternalServerError, entity = s"Error retrieving users: ${ex.getMessage}")
                  }
                }
              }
            },
            post {
              parameter("name") { name =>
                extractRequest { httpRequest =>
                  logger.info(s"POST request to create user: $name")

                  complete {
                    val createUserFuture: Future[Any] = userActor ? ClassicUserActor.CreateUser(name)
                    
                    createUserFuture.flatMap {
                      case ClassicUserActor.UserCreated(user) =>
                        logger.info(s"User created successfully: ${user.id}")
                        
                        // Send welcome notification and process events in parallel
                        val notificationFuture = sendNotification(user.id, s"Welcome ${user.name}!")
                        val eventsFuture = processUserEvents(user.id)
                        
                                                 for {
                           notificationResponse <- notificationFuture
                           eventsResults <- eventsFuture
                         } yield {
                           val responseJson = s"""{"user":${user.toJson.compactPrint},"onboarding":{"notification_sent":${notificationResponse.status == "sent"},"events_processed":${eventsResults.length},"notification_id":"${notificationResponse.id}"}}"""
                           HttpResponse(StatusCodes.Created, entity = responseJson)
                         }
                        
                      case _ =>
                        Future.successful(HttpResponse(StatusCodes.InternalServerError, entity = "Failed to create user"))
                    }.recover { case ex =>
                      HttpResponse(StatusCodes.InternalServerError, entity = s"Error creating user: ${ex.getMessage}")
                    }
                  }
                }
              }
            }
          )
        },
        pathPrefix("circuit-test") {
          get {
            extractRequest { httpRequest =>
              logger.info("GET request to test circuit breaker")

              complete {
                val future: Future[Any] = circuitBreakerActor ? "test"
                future.map {
                  case "SUCCESS" => HttpResponse(StatusCodes.OK, entity = "Circuit breaker: SUCCESS")
                  case "FAILURE" => HttpResponse(StatusCodes.ServiceUnavailable, entity = "Circuit breaker: FAILURE")
                  case "CIRCUIT_OPEN" => HttpResponse(StatusCodes.ServiceUnavailable, entity = "Circuit breaker: OPEN")
                  case _ => HttpResponse(StatusCodes.InternalServerError, entity = "Unexpected response")
                }.recover { case ex =>
                  HttpResponse(StatusCodes.InternalServerError, entity = s"Error testing circuit breaker: ${ex.getMessage}")
                }
              }
            }
          }
        },
        path(Segment) { id =>
          concat(
            get {
              extractRequest { httpRequest =>
                logger.info(s"GET request for user: $id")

                complete {
                  val future: Future[Any] = userActor ? ClassicUserActor.GetUser(id)
                  future.map {
                    case ClassicUserActor.UserFound(user) =>
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
    system.terminate()
    println("=== User Service stopped ===")
  }
  
  // Keep the application running (block main thread)
  scala.concurrent.Await.result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)
}