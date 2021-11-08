package com.lightbend.demo

import com.lightbend.demo.Protocol.{CommandResponse, ScheduleId, SchedulePayload, ScheduleRequest}
import spray.json.DefaultJsonProtocol

object JsonFormats {

  import DefaultJsonProtocol._

  implicit val scheduleId = jsonFormat1(ScheduleId)
  implicit val schedulePayload = jsonFormat1(SchedulePayload)
  implicit val scheduleRequest = jsonFormat8(ScheduleRequest)
  implicit val commandResponse = jsonFormat5(CommandResponse)

}
