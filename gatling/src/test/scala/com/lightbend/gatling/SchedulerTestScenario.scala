import com.typesafe.config.ConfigFactory
import java.util.UUID.randomUUID

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class SchedulerTestScenario extends Simulation {

  private val config =  ConfigFactory.load()

  val baseUrl = config.getString("loadtest.baseUrl")

  val scheduleIds = Iterator.continually(
    Map("scheduleId" -> randomUUID().toString)
  )

  val httpConf = http
    .baseUrl(s"${baseUrl}/scheduler")
    .acceptHeader("application/json")

  //30 x 9 for a 4.5hr run time
  val scheduleRequestBody = StringBody(
    """{
      |"scheduleId": {
      |  "id": "${scheduleId}"
      |},
      |"action": "none",
      |"name": "sample-schedule-${scheduleId}",
      |"duration": "1 minute",
      |"repeat": 5,
      |"grace": "none",
      |"topic": "na-${scheduleId}",
      |"payload": {
      |  "payload": "Payload: for ${scheduleId}"
      |}
      |}""".stripMargin)

  val scn = scenario("ScheduleScenario")
    .feed(scheduleIds)
    .exec(
      http("create_schedule")
        .post("/create")
        .body(scheduleRequestBody).asJson
        .check(status.is(200))
    )

  setUp(
    //scn.inject(atOnceUsers(100))

      scn.inject(
        atOnceUsers(1)
        //nothingFor(10 seconds),
        //rampUsers(1500000) during (2 hours)
      )
      .protocols(httpConf)
  )

}