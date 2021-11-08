package com.lightbend.demo

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.lightbend.demo.Protocol.{ScheduleId, ScheduleRequest, Status}

import scala.concurrent.duration.{Duration, FiniteDuration}

object ScheduleEntity {

  final val ScheduleEntityShardName = "SchedulerState"

  sealed trait BaseId extends MsgSerializeMarker {
    val scheduleId: ScheduleId
  }

  sealed trait ScheduleCommand extends BaseId
  sealed trait ScheduleQuery extends ScheduleCommand

  //commands
  final case class GetScheduleStatus(replyTo: ActorRef[ScheduleResponse], scheduleId: ScheduleId) extends ScheduleQuery
  final case class CreateSchedule(replyTo: ActorRef[ScheduleResponse], scheduleId: ScheduleId, scheduleData: ScheduleRequest) extends ScheduleCommand
  final case class ModifySchedule(replyTo: ActorRef[ScheduleResponse], scheduleId: ScheduleId, scheduleData: ScheduleRequest) extends ScheduleCommand
  final case class CancelSchedule(replyTo: ActorRef[ScheduleResponse], scheduleId: ScheduleId) extends ScheduleCommand

  //Internal Command
  private final case class ExecuteSchedule(scheduleId: ScheduleId, scheduleData: ScheduleRequest) extends ScheduleCommand

  //responses
  final case class ScheduleResponse(
    scheduleId: ScheduleId,
    status: String = Status.NOT_SCHEDULED.value,
    requested: Int = 0,
    executed: Int = 0,
    msg: Option[String] = None
  ) extends MsgSerializeMarker

  //events
  sealed trait ScheduleEvent extends EventSerializeMarker
  final case class ScheduleCreatedEvent(scheduleId: ScheduleId, scheduleData: ScheduleRequest, requested: Int, executed: Int) extends ScheduleEvent
  final case class ScheduleModifiedEvent(scheduleId: ScheduleId, scheduleData: ScheduleRequest, requested: Int, executed: Int) extends ScheduleEvent
  final case class ScheduleExecutedEvent(scheduleId: ScheduleId, scheduleData: ScheduleRequest, requested: Int, executed: Int) extends ScheduleEvent
  final case class ScheduleCompletedEvent(scheduleId: ScheduleId, scheduleData: ScheduleRequest, requested: Int, executed: Int) extends ScheduleEvent
  final case class ScheduleCancelledEvent(scheduleId: ScheduleId) extends ScheduleEvent

  //state
  sealed trait State extends MsgSerializeMarker

  final case object Empty extends State

  final case class Running(
    data: ScheduleRequest,
    requested: Int = 0,
    executed: Int = 0
  ) extends State

  final case class Cancelled(
    data: ScheduleRequest,
    requested: Int = 0,
    executed: Int = 0
  ) extends State

  final case class Completed(
    data: ScheduleRequest,
    requested: Int = 0,
    executed: Int = 0
  ) extends State

  def apply(scheduleId: ScheduleId): Behavior[ScheduleCommand] = Behaviors.setup[ScheduleCommand] { context =>

    Behaviors.withTimers { timers =>

      def commandHandler: (State, ScheduleCommand) => Effect[ScheduleEvent, State] = { (state, command) =>

        state match {
          //Initial state / for new schedules only
          case Empty =>
            command match {
              case GetScheduleStatus(replyTo, id) =>
                replyTo ! ScheduleResponse(id)
                Effect.none
              case CreateSchedule(replyTo, id, data) =>
                val duration = Duration(data.duration)
                val repeat = data.repeat
                if(duration.isFinite){
                  Effect.persist(ScheduleCreatedEvent(id, data, repeat, 0)).thenRun { _ =>
                    timers.startSingleTimer(
                      id,
                      ExecuteSchedule(id, data),
                      duration.asInstanceOf[FiniteDuration]
                    )
                    replyTo ! ScheduleResponse(id, Status.SCHEDULED.value, repeat)
                  }
                }
                else{
                  replyTo ! ScheduleResponse(id, Status.ERROR.value, repeat, 0, Some("Error: Incorrect schedule duration specified."))
                  Effect.none
                }
              case ModifySchedule(replyTo, id, data) =>
                replyTo ! ScheduleResponse(id, Status.ERROR.value, data.repeat, 0, Some("Error: No schedule exists to modify."))
                Effect.none
              case CancelSchedule(replyTo, id) =>
                replyTo ! ScheduleResponse(id, Status.ERROR.value, 0, 0, Some("Error: No schedule exists to cancel."))
                Effect.none
              case _ => Effect.unhandled
            }

          //Currently waiting for schedule to execute, be cancelled, or modified.
          case Running(_, requested, executed) =>
            command match {
              case GetScheduleStatus(replyTo, id) =>
                replyTo ! ScheduleResponse(id, Status.RUNNING.value, requested, executed)
                Effect.none
              case ExecuteSchedule(id, data) =>
                val exec = executed + 1
                val dur = Duration(data.duration)
                Effect.persist(ScheduleExecutedEvent(id, data, requested, exec)).thenRun { _ =>
                  //Do something to execute the Scheduled Task, based on [data]
                  //For the sake of demo, just logging output...
                  context.log.info(s"Schedule $id; Executed scheduled task. $exec time(s) out of $requested requested, every [ ${data.duration} ].")
                  if(requested > exec && dur.isFinite){
                    context.log.info(s"Schedule $id; Executing next scheduled task in [ ${data.duration} ].")
                    timers.startSingleTimer(
                      id,
                      ExecuteSchedule(id, data),
                      dur.asInstanceOf[FiniteDuration]
                    )
                  }
                  else {
                    context.log.info(s"Schedule $id completed, no more tasks to execute.")
                  }
                }
              case ModifySchedule(replyTo, scheduleId, scheduleData) =>
                val dur = Duration(scheduleData.duration)
                val repeat = scheduleData.repeat
                if(dur.isFinite){
                  //This would reset the execution count, basically resets the schedule with new data
                  Effect.persist(ScheduleModifiedEvent(scheduleId, scheduleData, repeat, 0)).thenRun { _ =>
                    timers.startSingleTimer(
                      scheduleId,
                      ExecuteSchedule(scheduleId, scheduleData),
                      dur.asInstanceOf[FiniteDuration]
                    )
                    replyTo ! ScheduleResponse(scheduleId, Status.RUNNING.value, repeat, 0)
                  }
                }
                else{
                  errorReply(replyTo, requested, executed,"Error: Incorrect schedule duration specified, leaving schedule unmodified.")
                  Effect.none
                }
              case CancelSchedule(replyTo, scheduleId) =>
                //Cancel the running schedule, will also take care of any inflight "ExecuteSchedule"
                Effect.persist(ScheduleCancelledEvent(scheduleId)).thenRun { _ =>
                  timers.cancel(scheduleId)
                  replyTo ! ScheduleResponse(scheduleId, Status.CANCELLED.value, requested, executed)
                }
              case CreateSchedule(replyTo, id, _) =>
                errorReply(replyTo, requested, executed,"Error: Schedule is currently running, use ModifySchedule to update running schedules.")
                Effect.none
            }

          case Cancelled(_, requested, executed) =>
            command match {
              case GetScheduleStatus(replyTo, id) =>
                replyTo ! ScheduleResponse(id, Status.CANCELLED.value, requested, executed)
                Effect.none
              case ModifySchedule(replyTo, scheduleId, scheduleData) =>
                val dur = Duration(scheduleData.duration)
                val repeat = scheduleData.repeat
                if(dur.isFinite){
                  //This would reset the execution count, basically resets the schedule with new data
                  Effect.persist(ScheduleModifiedEvent(scheduleId, scheduleData, repeat, 0)).thenRun { _ =>
                    timers.startSingleTimer(
                      scheduleId,
                      ExecuteSchedule(scheduleId, scheduleData),
                      dur.asInstanceOf[FiniteDuration]
                    )
                    replyTo ! ScheduleResponse(scheduleId, Status.RUNNING.value, scheduleData.repeat)
                  }
                }
                else{
                  errorReply(replyTo, requested, executed,"Error: Incorrect schedule duration specified, leaving schedule unmodified.")
                  Effect.none
                }
              case CancelSchedule(replyTo, id) =>
                errorReply(replyTo, requested, executed,"Error: Schedule is already cancelled, use ModifySchedule to restart schedules.")
                Effect.none
              case CreateSchedule(replyTo, id, _) =>
                errorReply(replyTo, requested, executed,"Error: Schedule is currently cancelled, use ModifySchedule to restart schedules.")
                Effect.none
            }

          case Completed(_, requested, executed) =>
            command match {
              case GetScheduleStatus(replyTo, id) =>
                replyTo ! ScheduleResponse(id, Status.COMPLETED.value, requested, executed)
                Effect.none
              case ModifySchedule(replyTo, _, _) =>
                errorReply(replyTo, requested, executed,"Error: Schedule has already completed, please specify a new schedule.")
                Effect.none
              case CancelSchedule(replyTo, _) =>
                errorReply(replyTo, requested, executed,"Error: Schedule has already completed, cannot cancel a completed schedule.")
                Effect.none
              case CreateSchedule(replyTo, _, _) =>
                errorReply(replyTo, requested, executed,"Error: Schedule has already completed, please specify a new schedule.")
                Effect.none
            }

        }
      }

      def errorReply(replyTo: ActorRef[ScheduleResponse], requested: Int, executed: Int, msg: String): Unit = {
        replyTo ! ScheduleResponse(scheduleId, Status.ERROR.value, requested, executed, Some(msg))
      }

      def eventHandler: (State, ScheduleEvent) => State = { (state, event) => state match {

        case Empty =>
          event match {
            case ScheduleCreatedEvent(_, data, requested, executed) =>
              Running(data, requested, executed)
            case _ => state
          }

        case Running(data, requested, executed) =>
          event match {
            case ScheduleModifiedEvent(_, data, requested, executed) =>
              Running(data, requested, executed)
            case ScheduleExecutedEvent(_, data, requested, executed) =>
              if(requested > 0 && requested > executed)
                Running(data, requested, executed)
              else
                Completed(data, requested, executed)
            case ScheduleCancelledEvent(_) =>
              Cancelled(data, requested, executed)
            case _ => state
          }

        case Cancelled(_, _, _) =>
          event match {
            case ScheduleModifiedEvent(_, data, requested, executed) =>
              Running(data, requested, executed)
            case _ => state
          }

        case Completed(_, _, _) => state

      }}

      EventSourcedBehavior[ScheduleCommand, ScheduleEvent, State](
        persistenceId = PersistenceId(ScheduleEntityShardName, scheduleId.id),
        emptyState = Empty,
        commandHandler,
        eventHandler
      )
      .receiveSignal {
        case (state: Running, RecoveryCompleted) =>
          val dur = Duration(state.data.duration)
          if(dur.isFinite){
            timers.startSingleTimer(
              scheduleId,
              ExecuteSchedule(scheduleId, state.data),
              dur.asInstanceOf[FiniteDuration]
            )
          }
      }

    }

  }

}
