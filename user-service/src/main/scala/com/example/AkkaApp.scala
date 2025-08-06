package com.example

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, Effect}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Source
import akka.util.Timeout
import kamon.Kamon
import kamon.context.Context
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.unmarshalling.Unmarshal

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class NotificationRequest(userId: String, message: String, channel: String)
case class NotificationResponse(id: String, status: String, timestamp: Long)

object NotificationRequest {
  implicit val format: RootJsonFormat[NotificationRequest] = jsonFormat3(NotificationRequest.apply)
}

object NotificationResponse {
  implicit val format: RootJsonFormat[NotificationResponse] = jsonFormat3(NotificationResponse.apply)
}

object UserActor {
  // Commands
  sealed trait Command
  case class GetUser(id: String, replyTo: ActorRef[UserResponse]) extends Command
  case class CreateUser(name: String, replyTo: ActorRef[UserResponse]) extends Command
  case class UpdateUser(id: String, name: String, replyTo: ActorRef[UserResponse]) extends Command
  case class DeleteUser(id: String, replyTo: ActorRef[UserResponse]) extends Command

  // Events
  sealed trait UserEvent
  case class UserCreatedEvent(id: String, name: String, timestamp: Long) extends UserEvent
  case class UserUpdatedEvent(id: String, name: String, timestamp: Long) extends UserEvent
  case class UserDeletedEvent(id: String, timestamp: Long) extends UserEvent

  // Responses
  sealed trait UserResponse
  case class User(id: String, name: String) extends UserResponse
  case class UserCreated(user: User) extends UserResponse
  case class UserUpdated(user: User) extends UserResponse
  case class UserDeleted(id: String) extends UserResponse
  case object UserNotFound extends UserResponse

  // State
  case class UserState(users: Map[String, User] = Map.empty) {
    def applyEvent(event: UserEvent): UserState = event match {
      case UserCreatedEvent(id, name, _) =>
        val user = User(id, name)
        copy(users = users + (id -> user))
      case UserUpdatedEvent(id, name, _) =>
        users.get(id) match {
          case Some(_) => copy(users = users + (id -> User(id, name)))
          case None => this
        }
      case UserDeletedEvent(id, _) =>
        copy(users = users - id)
    }
  }

  def apply(): Behavior[Command] = {
    EventSourcedBehavior[Command, UserEvent, UserState](
      persistenceId = PersistenceId.ofUniqueId("user-registry"),
      emptyState = UserState(),
      commandHandler = { (state, command) =>
        command match {
          case GetUser(id, replyTo) =>
            val span = Kamon.spanBuilder("get-user-persistence")
              .tag("user.id", id)
              .tag("persistence.operation", "get")
              .start()
            
            state.users.get(id) match {
              case Some(user) => 
                span.tag("user.found", true)
                span.finish()
                replyTo ! user
              case None => 
                span.tag("user.found", false)
                span.finish()
                replyTo ! UserNotFound
            }
            Effect.none

          case CreateUser(name, replyTo) =>
            val span = Kamon.spanBuilder("create-user-persistence")
              .tag("user.name", name)
              .tag("persistence.operation", "create")
              .start()
            
            val id = java.util.UUID.randomUUID().toString
            val event = UserCreatedEvent(id, name, System.currentTimeMillis())
            
            span.tag("user.id", id)
            span.tag("event.type", "UserCreatedEvent")
            
            Effect.persist(event).thenReply(replyTo) { updatedState =>
              val user = User(id, name)
              span.finish()
              UserCreated(user)
            }

          case UpdateUser(id, name, replyTo) =>
            val span = Kamon.spanBuilder("update-user-persistence")
              .tag("user.id", id)
              .tag("user.name", name)
              .tag("persistence.operation", "update")
              .start()
            
            state.users.get(id) match {
              case Some(_) =>
                val event = UserUpdatedEvent(id, name, System.currentTimeMillis())
                span.tag("event.type", "UserUpdatedEvent")
                
                Effect.persist(event).thenReply(replyTo) { updatedState =>
                  val user = User(id, name)
                  span.finish()
                  UserUpdated(user)
                }
              case None =>
                span.tag("user.found", false)
                span.finish()
                replyTo ! UserNotFound
                Effect.none
            }

          case DeleteUser(id, replyTo) =>
            val span = Kamon.spanBuilder("delete-user-persistence")
              .tag("user.id", id)
              .tag("persistence.operation", "delete")
              .start()
            
            state.users.get(id) match {
              case Some(_) =>
                val event = UserDeletedEvent(id, System.currentTimeMillis())
                span.tag("event.type", "UserDeletedEvent")
                
                Effect.persist(event).thenReply(replyTo) { updatedState =>
                  span.finish()
                  UserDeleted(id)
                }
              case None =>
                span.tag("user.found", false)
                span.finish()
                replyTo ! UserNotFound
                Effect.none
            }
        }
      },
      eventHandler = { (state, event) =>
        val span = Kamon.spanBuilder("apply-event")
          .tag("event.type", event.getClass.getSimpleName)
          .tag("persistence.operation", "event-handler")
          .start()
        
        val newState = state.applyEvent(event)
        span.finish()
        newState
      }
    )
  }
}

object AkkaApp extends App with LazyLogging {
  
  System.out.println("=== USER SERVICE STARTING ===")
  Kamon.init()
  System.out.println("=== KAMON INITIALIZED ===")
  
  implicit val system: ActorSystem[UserActor.Command] = ActorSystem(UserActor(), "user-system")
  implicit val executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout(5.seconds)

  val userActor = system
  
  // HTTP client for calling notification service
  val http = Http(system)
  
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
        path(Segment) { userId =>
          concat(
            get {
              extractRequest { httpRequest =>
                val span = Kamon.spanBuilder("get-user-request")
                  .tag("http.method", "GET")
                  .tag("http.path", s"/api/users/$userId")
                  .start()
                
                logger.info(s"GET request for user: $userId")
                complete {
                  import akka.actor.typed.scaladsl.AskPattern._
                  val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.GetUser(userId, _))
                  future.map {
                    case user: UserActor.User => 
                      span.tag("user.found", true)
                      span.finish()
                      HttpResponse(StatusCodes.OK, entity = s"User: ${user.name}")
                    case UserActor.UserNotFound => 
                      span.tag("user.found", false)
                      span.finish()
                      HttpResponse(StatusCodes.NotFound, entity = "User not found")
                  }
                }
              }
            },
            put {
              parameter("name") { name =>
                extractRequest { httpRequest =>
                  val span = Kamon.spanBuilder("update-user-request")
                    .tag("http.method", "PUT")
                    .tag("http.path", s"/api/users/$userId")
                    .tag("user.name", name)
                    .start()
                  
                  logger.info(s"PUT request to update user: $userId with name: $name")
                  complete {
                    import akka.actor.typed.scaladsl.AskPattern._
                    val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.UpdateUser(userId, name, _))
                    future.map {
                      case UserActor.UserUpdated(user) => 
                        span.tag("user.updated", true)
                        span.finish()
                        HttpResponse(StatusCodes.OK, entity = s"Updated user: ${user.name}")
                      case UserActor.UserNotFound => 
                        span.tag("user.found", false)
                        span.finish()
                        HttpResponse(StatusCodes.NotFound, entity = "User not found")
                    }
                  }
                }
              }
            },
            delete {
              extractRequest { httpRequest =>
                val span = Kamon.spanBuilder("delete-user-request")
                  .tag("http.method", "DELETE")
                  .tag("http.path", s"/api/users/$userId")
                  .start()
                
                logger.info(s"DELETE request for user: $userId")
                complete {
                  import akka.actor.typed.scaladsl.AskPattern._
                  val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.DeleteUser(userId, _))
                  future.map {
                    case UserActor.UserDeleted(id) => 
                      span.tag("user.deleted", true)
                      span.finish()
                      HttpResponse(StatusCodes.OK, entity = s"Deleted user: $id")
                    case UserActor.UserNotFound => 
                      span.tag("user.found", false)
                      span.finish()
                      HttpResponse(StatusCodes.NotFound, entity = "User not found")
                  }
                }
              }
            }
          )
        },
        post {
          parameter("name") { name =>
            extractRequest { httpRequest =>
              val span = Kamon.spanBuilder("create-user-request")
                .tag("http.method", "POST")
                .tag("http.path", "/api/users")
                .tag("user.name", name)
                .start()
              
              logger.info(s"POST request to create user: $name")
              complete {
                import akka.actor.typed.scaladsl.AskPattern._
                val future: Future[UserActor.UserResponse] = userActor.ask(UserActor.CreateUser(name, _))
                future.flatMap {
                  case UserActor.UserCreated(user) => 
                    logger.info(s"User created with id: ${user.id}, sending welcome notification")
                    span.tag("user.id", user.id)
                    
                    sendNotification(user.id, s"Welcome ${user.name}! Your account has been created.", span).map { notificationResponse =>
                      logger.info(s"Notification sent successfully: ${notificationResponse.id}")
                      span.tag("notification.sent", true)
                      span.tag("notification.id", notificationResponse.id)
                      span.finish()
                      HttpResponse(StatusCodes.Created, entity = s"Created user: ${user.name} with id: ${user.id}. Notification sent: ${notificationResponse.id}")
                    }.recover {
                      case ex =>
                        logger.error("Failed to send notification", ex)
                        span.tag("notification.sent", false)
                        span.tag("error", true)
                        span.tag("error.message", ex.getMessage)
                        span.finish()
                        HttpResponse(StatusCodes.Created, entity = s"Created user: ${user.name} with id: ${user.id}. Notification failed: ${ex.getMessage}")
                    }
                  case _ => 
                    span.tag("error", true)
                    span.finish()
                    Future.successful(HttpResponse(StatusCodes.InternalServerError))
                }
              }
            }
          }
        }
      )
    } ~
    path("health") {
      get {
        complete(HttpResponse(StatusCodes.OK, entity = "OK"))
      }
    }

  val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(route)

  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      logger.info(s"Server online at http://${address.getHostString}:${address.getPort}/")
    case Failure(ex) =>
      logger.error("Failed to bind HTTP endpoint", ex)
      system.terminate()
  }

  sys.addShutdownHook {
    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => {
        Kamon.stop()
        system.terminate()
      })
  }
}