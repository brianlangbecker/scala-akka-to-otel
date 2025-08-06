package com.example.notification

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

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class NotificationRequest(userId: String, message: String, channel: String)
case class NotificationResponse(id: String, status: String, timestamp: Long)

object JsonFormats {
  import spray.json.DefaultJsonProtocol._
  implicit val notificationRequestFormat: RootJsonFormat[NotificationRequest] = jsonFormat3(NotificationRequest)
  implicit val notificationResponseFormat: RootJsonFormat[NotificationResponse] = jsonFormat3(NotificationResponse)
}

object NotificationActor {
  sealed trait Command
  case class SendNotification(request: NotificationRequest, replyTo: ActorRef[NotificationResponse]) extends Command

  def apply(): Behavior[Command] = {
    Behaviors.receive { (context, message) =>
      message match {
        case SendNotification(request, replyTo) =>
          
          context.log.info(s"Sending notification to user ${request.userId} via ${request.channel}: ${request.message}")
          
          Thread.sleep(100)
          
          val response = NotificationResponse(
            id = java.util.UUID.randomUUID().toString,
            status = "sent",
            timestamp = System.currentTimeMillis()
          )
          
          context.log.info(s"Notification sent with id: ${response.id}")
          
          replyTo ! response
          Behaviors.same
      }
    }
  }
}

object NotificationApp extends App with LazyLogging {
  import JsonFormats._

  println("Starting notification service typed")
  
  Kamon.init()
  
  println("Kamon initialized successfully in notification service typed")

  implicit val system: ActorSystem[NotificationActor.Command] =
    ActorSystem(NotificationActor(), "notification-system")
  implicit val executionContext: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = 3.seconds

  val notificationActor: ActorRef[NotificationActor.Command] = system

  val route =
    pathPrefix("api" / "notifications") {
      concat(
        post {
          entity(as[NotificationRequest]) { request =>
            extractRequest { httpRequest =>
              
              logger.info(s"POST request to send notification: $request")
              
              complete {
                import akka.actor.typed.scaladsl.AskPattern._
                val future: Future[NotificationResponse] = notificationActor.ask(NotificationActor.SendNotification(request, _))
                future.map { response =>
                  HttpResponse(StatusCodes.OK, entity = response.toJson.compactPrint)
                }.recover { case ex =>
                  HttpResponse(StatusCodes.InternalServerError)
                }
              }
            }
          }
        }
      )
    }

  val bindingFuture = Http().newServerAt("0.0.0.0", 8081).bind(route)
  
  println(s"Notification Service online at http://localhost:8081/")
  
  sys.addShutdownHook {
    println("=== Stopping Kamon in Notification Service Typed ===")
    Kamon.stop()
    system.terminate()
    println("=== Notification Service Typed stopped ===")
  }
  
  // Keep the application running (block main thread)
  scala.concurrent.Await.result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)
}