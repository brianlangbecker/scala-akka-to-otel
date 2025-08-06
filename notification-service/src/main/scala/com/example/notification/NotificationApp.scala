package com.example.notification

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import kamon.Kamon
import kamon.context.Context
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

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

object NotificationActor {
  sealed trait Command
  case class SendNotification(request: NotificationRequest, replyTo: ActorRef[NotificationResponse]) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case SendNotification(request, replyTo) =>
          val span = Kamon.spanBuilder("send-notification")
            .tag("user.id", request.userId)
            .tag("notification.channel", request.channel)
            .start()
          
          context.log.info(s"Sending notification to user ${request.userId} via ${request.channel}: ${request.message}")
          
          // Simulate notification processing delay
          Thread.sleep(100)
          
          val response = NotificationResponse(
            id = java.util.UUID.randomUUID().toString,
            status = "sent",
            timestamp = System.currentTimeMillis()
          )
          
          context.log.info(s"Notification sent with id: ${response.id}")
          span.tag("notification.id", response.id)
          span.finish()
          
          replyTo ! response
          Behaviors.same
      }
    }
  }
}

object NotificationApp extends App with LazyLogging {
  
  // Initialize Kamon
  println("Starting notification service - about to initialize Kamon")
  Kamon.init()
  println("Kamon initialized successfully in notification service")
  
  implicit val system: ActorSystem[NotificationActor.Command] = ActorSystem(NotificationActor(), "notification-system")
  implicit val executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout(5.seconds)

  val notificationActor = system

  val route =
    pathPrefix("api" / "notifications") {
      concat(
        post {
          entity(as[NotificationRequest]) { request =>
            extractRequest { httpRequest =>
              val span = Kamon.spanBuilder("notification-request")
                .tag("http.method", "POST")
                .tag("http.path", "/api/notifications")
                .start()
              
              logger.info(s"POST request to send notification: $request")
              
              complete {
                import akka.actor.typed.scaladsl.AskPattern._
                val future: Future[NotificationResponse] = notificationActor.ask(NotificationActor.SendNotification(request, _))
                future.map { response =>
                  span.tag("notification.id", response.id)
                  span.tag("notification.status", response.status)
                  span.finish()
                  HttpResponse(StatusCodes.OK, entity = response.toJson.compactPrint)
                }.recover { case ex =>
                  span.tag("error", true)
                  span.tag("error.message", ex.getMessage)
                  span.finish()
                  HttpResponse(StatusCodes.InternalServerError)
                }
              }
            }
          }
        }
      )
    } ~
    path("health") {
      get {
        complete(HttpResponse(StatusCodes.OK, entity = "Notification Service OK"))
      }
    }

  val bindingFuture = Http().newServerAt("0.0.0.0", 8081).bind(route)

  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      logger.info(s"Notification Service online at http://${address.getHostString}:${address.getPort}/")
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