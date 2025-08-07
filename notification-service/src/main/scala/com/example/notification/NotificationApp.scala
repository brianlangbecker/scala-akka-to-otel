package com.example.notification

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import akka.pattern.ask
import com.typesafe.scalalogging.LazyLogging
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import kamon.Kamon

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class NotificationRequest(userId: String, message: String, channel: String)
case class NotificationResponse(id: String, status: String, timestamp: Long)

object JsonFormats {
  implicit val notificationRequestFormat: RootJsonFormat[NotificationRequest] = jsonFormat3(NotificationRequest)
  implicit val notificationResponseFormat: RootJsonFormat[NotificationResponse] = jsonFormat3(NotificationResponse)
}

// Classic Akka Actor
object NotificationActor {
  case class SendNotification(request: NotificationRequest)
  def props: Props = Props[NotificationActor]()
}

class NotificationActor extends Actor with LazyLogging {
  import NotificationActor._
  
  def receive: Receive = {
    case SendNotification(request) =>
      logger.info(s"Processing notification for user ${request.userId}")
      val response = NotificationResponse(
        id = java.util.UUID.randomUUID().toString,
        status = "sent",
        timestamp = System.currentTimeMillis()
      )
      sender() ! response
  }
}

object NotificationApp extends App with LazyLogging {
  import JsonFormats._
  
  println("Starting notification service - about to initialize Kamon")
  Kamon.init()
  println("Kamon initialized successfully in notification service")
  
  // Create classic actor system
  implicit val system: ActorSystem = ActorSystem("notification-system")
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(5.seconds)
  
  val notificationActor: ActorRef = system.actorOf(NotificationActor.props, "notification-actor")
  
  val routes = 
    path("health") {
      get {
        complete("OK")
      }
    } ~
    pathPrefix("api" / "notifications") {
      concat(
        post {
          entity(as[NotificationRequest]) { request =>
            extractRequest { httpRequest =>
              
              logger.info(s"POST request to send notification: $request")
              
              complete {
                val future: Future[NotificationResponse] = (notificationActor ? NotificationActor.SendNotification(request)).mapTo[NotificationResponse]
                future.map { response =>
                  HttpResponse(StatusCodes.OK, entity = response.toJson.compactPrint)
                }
              }
            }
          }
        },
        get {
          complete("Notification service is running")
        }
      )
    }

  val bindingFuture = Http().newServerAt("0.0.0.0", 8081).bind(routes)
  
  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      logger.info(s"Notification Service online at http://${address.getHostString}:${address.getPort}/")
    case Failure(ex) =>
      logger.error("Failed to bind HTTP endpoint", ex)
      system.terminate()
  }

  // Keep the application running
  scala.concurrent.Await.result(system.whenTerminated, scala.concurrent.duration.Duration.Inf)
}