package com.example

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import kamon.Kamon
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.stream.scaladsl.{Source, Flow, Sink}
import akka.NotUsed

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Random}

case class NotificationRequest(userId: String, message: String, channel: String)
case class NotificationResponse(id: String, status: String, timestamp: Long)

// Data processing models
case class UserEvent(userId: String, eventType: String, data: String, timestamp: Long)
case class ProcessedEvent(userId: String, eventType: String, processedData: String, 
                         processingTime: Long, timestamp: Long, batchId: String)

object NotificationRequest {
  implicit val format: RootJsonFormat[NotificationRequest] = jsonFormat3(NotificationRequest.apply)
}

object NotificationResponse {
  implicit val format: RootJsonFormat[NotificationResponse] = jsonFormat3(NotificationResponse.apply)
}

object UserEvent {
  implicit val format: RootJsonFormat[UserEvent] = jsonFormat4(UserEvent.apply)
}

object ProcessedEvent {
  implicit val format: RootJsonFormat[ProcessedEvent] = jsonFormat6(ProcessedEvent.apply)
}

object UserActor {
  // Commands
  sealed trait Command
  case class GetUser(id: String, replyTo: ActorRef[UserResponse]) extends Command
  case class CreateUser(name: String, replyTo: ActorRef[UserResponse]) extends Command
  case class UpdateUser(id: String, name: String, replyTo: ActorRef[UserResponse]) extends Command
  case class DeleteUser(id: String, replyTo: ActorRef[UserResponse]) extends Command

  // Responses
  sealed trait UserResponse
  case class User(id: String, name: String) extends UserResponse
  case class UserCreated(user: User) extends UserResponse
  case class UserUpdated(user: User) extends UserResponse
  case class UserDeleted(id: String) extends UserResponse
  case object UserNotFound extends UserResponse

  def apply(): Behavior[Command] = {
    def behavior(users: Map[String, User]): Behavior[Command] = {
      Behaviors.receive { (context, message) =>
        message match {
          case GetUser(id, replyTo) =>
            val span = Kamon.spanBuilder("get-user")
              .tag("user.id", id)
              .start()
            
            context.log.info(s"Getting user with id: $id")
            users.get(id) match {
              case Some(user) => 
                span.tag("user.found", true)
                span.finish()
                replyTo ! user
              case None => 
                span.tag("user.found", false)
                span.finish()
                replyTo ! UserNotFound
            }
            Behaviors.same

          case CreateUser(name, replyTo) =>
            val span = Kamon.spanBuilder("create-user")
              .tag("user.name", name)
              .start()
            
            val id = java.util.UUID.randomUUID().toString
            val user = User(id, name)
            context.log.info(s"Creating user: $user")
            
            span.tag("user.id", id)
            span.finish()
            
            replyTo ! UserCreated(user)
            behavior(users + (id -> user))

          case UpdateUser(id, name, replyTo) =>
            val span = Kamon.spanBuilder("update-user")
              .tag("user.id", id)
              .tag("user.name", name)
              .start()
            
            users.get(id) match {
              case Some(_) =>
                val updatedUser = User(id, name)
                context.log.info(s"Updating user: $updatedUser")
                span.tag("user.found", true)
                span.finish()
                replyTo ! UserUpdated(updatedUser)
                behavior(users + (id -> updatedUser))
              case None =>
                span.tag("user.found", false)
                span.finish()
                replyTo ! UserNotFound
                Behaviors.same
            }

          case DeleteUser(id, replyTo) =>
            val span = Kamon.spanBuilder("delete-user")
              .tag("user.id", id)
              .start()
            
            users.get(id) match {
              case Some(_) =>
                context.log.info(s"Deleting user with id: $id")
                span.tag("user.found", true)
                span.finish()
                replyTo ! UserDeleted(id)
                behavior(users - id)
              case None =>
                span.tag("user.found", false)
                span.finish()
                replyTo ! UserNotFound
                Behaviors.same
            }
        }
      }
    }
    behavior(Map.empty)
  }
}

object DataProcessingService {
  def createEventProcessingPipeline()(implicit ec: ExecutionContext): Flow[UserEvent, ProcessedEvent, NotUsed] = {
    
    val validateStage = Flow[UserEvent].map { event =>
      val span = Kamon.spanBuilder("stream-validate")
        .tag("user.id", event.userId)
        .tag("event.type", event.eventType)
        .start()
      
      Thread.sleep(10) // Simulate validation processing
      
      span.tag("validation.result", "valid")
      span.finish()
      event
    }
    
    val enrichStage = Flow[UserEvent].map { event =>
      val span = Kamon.spanBuilder("stream-enrich")
        .tag("user.id", event.userId)
        .tag("event.type", event.eventType)
        .start()
      
      Thread.sleep(20) // Simulate enrichment processing
      val enrichedData = s"enriched-${event.data}-${Random.nextInt(1000)}"
      
      span.tag("enrichment.added", "metadata")
      span.finish()
      event.copy(data = enrichedData)
    }
    
    val transformStage = Flow[UserEvent].map { event =>
      val span = Kamon.spanBuilder("stream-transform")
        .tag("user.id", event.userId)
        .tag("event.type", event.eventType)
        .start()
      
      val startTime = System.currentTimeMillis()
      Thread.sleep(15) // Simulate transformation processing
      val processingTime = System.currentTimeMillis() - startTime
      
      val processedEvent = ProcessedEvent(
        userId = event.userId,
        eventType = event.eventType,
        processedData = s"processed-${event.data}",
        processingTime = processingTime,
        timestamp = event.timestamp,
        batchId = java.util.UUID.randomUUID().toString
      )
      
      span.tag("processing.time_ms", processingTime)
      span.tag("batch.id", processedEvent.batchId)
      span.finish()
      processedEvent
    }
    
    validateStage
      .via(enrichStage)
      .via(transformStage)
  }
  
  def processEventBatch(events: List[UserEvent])(implicit system: ActorSystem[_], ec: ExecutionContext): Future[List[ProcessedEvent]] = {
    val span = Kamon.spanBuilder("stream-process-batch")
      .tag("batch.size", events.length)
      .start()
    
    Source(events)
      .via(createEventProcessingPipeline())
      .runWith(Sink.collection)
      .map { processedEvents =>
        span.tag("batch.processed_count", processedEvents.size)
        span.finish()
        processedEvents.toList
      }
      .recover { case ex =>
        span.tag("error", true)
        span.tag("error.message", ex.getMessage)
        span.finish()
        throw ex
      }
  }
}

object AkkaApp extends App with LazyLogging {
  println("=== USER SERVICE STARTING ===")
  
  // Initialize Kamon
  Kamon.init()
  println("=== KAMON INITIALIZED ===")

  implicit val system: ActorSystem[UserActor.Command] = ActorSystem(UserActor(), "user-system")
  implicit val executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout(5.seconds)

  val userActor = system
  val http = Http()(system)

  // JSON formats
  implicit val userFormat: RootJsonFormat[UserActor.User] = jsonFormat2(UserActor.User.apply)

  def sendNotification(userId: String, message: String, parentSpan: kamon.trace.Span): Future[NotificationResponse] = {
    val span = Kamon.spanBuilder("send-welcome-notification")
      .asChildOf(parentSpan)
      .tag("user.id", userId)
      .start()
    
    val request = NotificationRequest(userId, message, "email")
    val requestJson = request.toJson.compactPrint
    logger.info(s"Sending notification request: $requestJson")
    
    val httpRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = "http://notification-service:8081/api/notifications",
      entity = HttpEntity(ContentTypes.`application/json`, requestJson)
    )
    logger.info(s"Making HTTP request to: ${httpRequest.uri}")
    
    http.singleRequest(httpRequest).flatMap { response =>
      logger.info(s"Received response with status: ${response.status}")
      span.tag("http.status_code", response.status.intValue())
      
      response.entity.dataBytes.runFold(new StringBuilder)(_ append _.utf8String).flatMap { body =>
        logger.info(s"Response body: $body")
        if (response.status.isSuccess()) {
          val notificationResponse = body.toString.parseJson.convertTo[NotificationResponse]
          span.tag("notification.id", notificationResponse.id)
          span.finish()
          Future.successful(notificationResponse)
        } else {
          span.tag("error", true)
          span.tag("error.message", s"Failed with status ${response.status} and body: $body")
          span.finish()
          Future.failed(new RuntimeException(s"Notification request failed with status ${response.status}"))
        }
      }
    }.recover { case ex =>
      logger.error("Failed to send notification", ex)
      span.tag("error", true)
      span.tag("error.message", ex.getMessage)
      span.finish()
      throw ex
    }
  }

  val route =
    pathPrefix("api" / "users") {
      concat(
        post {
          parameter("name") { name =>
            val span = Kamon.spanBuilder("create-user-request")
              .tag("user.name", name)
              .tag("http.method", "POST")
              .tag("http.path", "/api/users")
              .start()
            
            logger.info(s"POST request to create user with name: $name")
            
            complete {
              import akka.actor.typed.scaladsl.AskPattern._
              val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.CreateUser(name, _))
              future.flatMap {
                case UserActor.UserCreated(user) =>
                  span.tag("user.id", user.id)
                  span.tag("user.created", true)
                  
                  // Send welcome notification
                  sendNotification(user.id, s"Welcome ${user.name}!", span).map { notificationResponse =>
                    span.tag("notification.id", notificationResponse.id)
                    span.finish()
                    HttpResponse(StatusCodes.OK, entity = s"User created: ${user.toJson.compactPrint}, Notification: ${notificationResponse.toJson.compactPrint}")
                  }.recover { case ex =>
                    logger.error("Notification failed", ex)
                    span.tag("notification.error", true)
                    span.finish()
                    HttpResponse(StatusCodes.OK, entity = s"User created: ${user.toJson.compactPrint}, Notification failed: ${ex.getMessage}")
                  }
                case other =>
                  span.tag("user.created", false)
                  span.finish()
                  Future.successful(HttpResponse(StatusCodes.BadRequest, entity = s"Failed to create user: $other"))
              }.recover { case ex =>
                span.tag("error", true)
                span.tag("error.message", ex.getMessage)
                span.finish()
                HttpResponse(StatusCodes.InternalServerError)
              }
            }
          }
        },
        path(Segment) { id =>
          concat(
            get {
              val span = Kamon.spanBuilder("get-user-request")
                .tag("user.id", id)
                .tag("http.method", "GET")
                .tag("http.path", s"/api/users/$id")
                .start()
              
              logger.info(s"GET request for user with id: $id")
              
              complete {
                import akka.actor.typed.scaladsl.AskPattern._
                val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.GetUser(id, _))
                future.map {
                  case user: UserActor.User =>
                    span.tag("user.found", true)
                    span.finish()
                    HttpResponse(StatusCodes.OK, entity = user.toJson.compactPrint)
                  case UserActor.UserNotFound =>
                    span.tag("user.found", false)
                    span.finish()
                    HttpResponse(StatusCodes.NotFound, entity = "User not found")
                  case other =>
                    span.tag("error", true)
                    span.finish()
                    HttpResponse(StatusCodes.InternalServerError, entity = s"Unexpected response: $other")
                }.recover { case ex =>
                  span.tag("error", true)
                  span.tag("error.message", ex.getMessage)
                  span.finish()
                  HttpResponse(StatusCodes.InternalServerError)
                }
              }
            },
            put {
              parameter("name") { name =>
                val span = Kamon.spanBuilder("update-user-request")
                  .tag("user.id", id)
                  .tag("user.name", name)
                  .tag("http.method", "PUT")
                  .tag("http.path", s"/api/users/$id")
                  .start()
                
                logger.info(s"PUT request to update user $id with name: $name")
                
                complete {
                  import akka.actor.typed.scaladsl.AskPattern._
                  val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.UpdateUser(id, name, _))
                  future.map {
                    case UserActor.UserUpdated(user) =>
                      span.tag("user.updated", true)
                      span.finish()
                      HttpResponse(StatusCodes.OK, entity = user.toJson.compactPrint)
                    case UserActor.UserNotFound =>
                      span.tag("user.found", false)
                      span.finish()
                      HttpResponse(StatusCodes.NotFound, entity = "User not found")
                    case other =>
                      span.tag("error", true)
                      span.finish()
                      HttpResponse(StatusCodes.InternalServerError, entity = s"Unexpected response: $other")
                  }.recover { case ex =>
                    span.tag("error", true)
                    span.tag("error.message", ex.getMessage)
                    span.finish()
                    HttpResponse(StatusCodes.InternalServerError)
                  }
                }
              }
            },
            delete {
              val span = Kamon.spanBuilder("delete-user-request")
                .tag("user.id", id)
                .tag("http.method", "DELETE")
                .tag("http.path", s"/api/users/$id")
                .start()
              
              logger.info(s"DELETE request for user with id: $id")
              
              complete {
                import akka.actor.typed.scaladsl.AskPattern._
                val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.DeleteUser(id, _))
                future.map {
                  case UserActor.UserDeleted(deletedId) =>
                    span.tag("user.deleted", true)
                    span.finish()
                    HttpResponse(StatusCodes.OK, entity = s"User $deletedId deleted")
                  case UserActor.UserNotFound =>
                    span.tag("user.found", false)
                    span.finish()
                    HttpResponse(StatusCodes.NotFound, entity = "User not found")
                  case other =>
                    span.tag("error", true)
                    span.finish()
                    HttpResponse(StatusCodes.InternalServerError, entity = s"Unexpected response: $other")
                }.recover { case ex =>
                  span.tag("error", true)
                  span.tag("error.message", ex.getMessage)
                  span.finish()
                  HttpResponse(StatusCodes.InternalServerError)
                }
              }
            }
          )
        }
      )
    } ~
    pathPrefix("api" / "streams") {
      concat(
        post {
          path("process-events") {
            entity(as[List[UserEvent]]) { events =>
              val span = Kamon.spanBuilder("process-events-request")
                .tag("http.method", "POST")
                .tag("http.path", "/api/streams/process-events")
                .tag("events.count", events.length)
                .start()
              
              logger.info(s"POST request to process ${events.length} events")
              
              complete {
                DataProcessingService.processEventBatch(events).map { processedEvents =>
                  span.tag("processed.count", processedEvents.length)
                  span.finish()
                  HttpResponse(StatusCodes.OK, entity = processedEvents.toJson.compactPrint)
                }.recover { case ex =>
                  span.tag("error", true)
                  span.tag("error.message", ex.getMessage)
                  span.finish()
                  HttpResponse(StatusCodes.InternalServerError, entity = s"Processing failed: ${ex.getMessage}")
                }
              }
            }
          }
        },
        get {
          path("generate-sample") {
            val span = Kamon.spanBuilder("generate-sample-events")
              .tag("http.method", "GET")
              .tag("http.path", "/api/streams/generate-sample")
              .start()
            
            logger.info("GET request to generate sample events")
            
            complete {
              val sampleEvents = (1 to 5).map { i =>
                UserEvent(
                  userId = s"user-$i",
                  eventType = if (i % 2 == 0) "login" else "purchase",
                  data = s"sample-data-$i",
                  timestamp = System.currentTimeMillis()
                )
              }.toList
              
              span.tag("generated.count", sampleEvents.length)
              span.finish()
              HttpResponse(StatusCodes.OK, entity = sampleEvents.toJson.compactPrint)
            }
          }
        }
      )
    }

  // Start the server
  val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(route)

  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      logger.info(s"Server online at http://${address.getHostString}:${address.getPort}/")
    case Failure(ex) =>
      logger.error("Failed to bind HTTP endpoint", ex)
      system.terminate()
  }
}