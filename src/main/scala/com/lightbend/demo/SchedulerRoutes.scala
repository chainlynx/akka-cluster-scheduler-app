package com.lightbend.demo

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.util.Timeout
import com.lightbend.demo.Protocol._
import com.lightbend.demo.ScheduleEntity._

import scala.concurrent.{ExecutionContextExecutor, Future}

class SchedulerRoutes(system: ActorSystem[Nothing], entityActor: ActorRef[ShardingEnvelope[ScheduleCommand]]) {

  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import JsonFormats._

  // If ask takes more time than this to complete the request is failed
  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))
  private implicit val ec: ExecutionContextExecutor = system.executionContext

  implicit val scheduler: Scheduler = system.scheduler

  def querySchedule(scheduleId: ScheduleId): Future[CommandResponse] = {
    val result = entityActor.ask { ref: ActorRef[ScheduleResponse] =>
      ShardingEnvelope(scheduleId.id, GetScheduleStatus(ref, scheduleId))
    }
    handleResponse(result)
  }

  def createSchedule(scheduleId: ScheduleId, request: ScheduleRequest): Future[CommandResponse] = {
    val result = entityActor.ask { ref: ActorRef[ScheduleResponse] =>
      ShardingEnvelope(scheduleId.id, CreateSchedule(ref, scheduleId, request))
    }
    handleResponse(result)
  }

  def modifySchedule(scheduleId: ScheduleId, request: ScheduleRequest): Future[CommandResponse] = {
    val result = entityActor.ask { ref: ActorRef[ScheduleResponse] =>
      ShardingEnvelope(scheduleId.id, ModifySchedule(ref, scheduleId, request))
    }
    handleResponse(result)
  }

  def cancelSchedule(scheduleId: ScheduleId): Future[CommandResponse] = {
    val result = entityActor.ask { ref: ActorRef[ScheduleResponse] =>
      ShardingEnvelope(scheduleId.id, CancelSchedule(ref, scheduleId))
    }
    handleResponse(result)
  }

  def handleResponse(f: Future[ScheduleResponse]): Future[CommandResponse] = {
    f.map {
      case ScheduleResponse(id, status, req, ex, msg) => {
        println(ScheduleResponse(id, status, req, ex, msg))
        CommandResponse(id, status, req, ex, msg)
      }
    }
  }

  implicit def exceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case ex: Exception =>
        extractUri { uri =>
          val msg = s"Request to $uri could not be handled normally: Exception: ${ex.getCause} : ${ex.getMessage}"
          system.log.error(msg)
          complete(HttpResponse(StatusCodes.InternalServerError, entity = msg))
        }
    }

  lazy val routes: Route =
    pathPrefix("scheduler") {
      concat(
        pathPrefix("search") {
          concat {
            get {
              parameters("scheduleId") { id =>
                complete(StatusCodes.OK, querySchedule(ScheduleId(id)))
              }
            }
          }
        },
        pathPrefix("create") {
          concat {
            post {
              entity(as[ScheduleRequest]) { req =>
                complete(StatusCodes.OK, createSchedule(req.scheduleId, req))
              }
            }
          }
        },
        pathPrefix("modify") {
          concat {
            post {
              entity(as[ScheduleRequest]) { req =>
                complete(StatusCodes.OK, modifySchedule(req.scheduleId, req))
              }
            }
          }
        },
        pathPrefix("cancel") {
          concat {
            delete {
              parameters("scheduleId") { id =>
                complete(StatusCodes.OK, cancelSchedule(ScheduleId(id)))
              }
            }
          }
        }
      )
    }

}
