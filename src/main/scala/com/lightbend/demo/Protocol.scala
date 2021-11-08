package com.lightbend.demo

object Protocol {

  case class ScheduleId(id: String = "") extends AnyVal
  case class SchedulePayload(payload: String = "") extends AnyVal

  sealed abstract class Status(val name: String, val value: String)
  object Status {
    case object PENDING extends Status("PENDING", "Pending")
    case object SCHEDULED extends Status("SCHEDULED", "Scheduled")
    case object RUNNING extends Status("RUNNING", "Running")
    case object COMPLETED extends Status("COMPLETED", "Completed")
    case object CANCELLED extends Status("CANCELLED", "Cancelled")
    case object NOT_SCHEDULED extends Status("NOT_SCHEDULED", "Not Scheduled")
    case object ERROR extends Status("ERROR", "Error")
  }

  sealed trait Request
  final case class ScheduleRequest(
    scheduleId: ScheduleId = ScheduleId(""),
    action: String = "", //not currently used
    name: String = "",   //not currently used
    duration: String = "",
    repeat: Int = 0,
    grace: String = "",  //not currently used
    topic: String = "",  //not currently used
    payload: SchedulePayload = SchedulePayload("") //not currently used
  ) extends Request

  sealed trait Response
  final case class CommandResponse(
    scheduleId: ScheduleId = ScheduleId(""),
    status: String = Status.NOT_SCHEDULED.value,
    requested: Int = 0,
    executed: Int = 0,
    failure: Option[String] = None
  ) extends Response

}
