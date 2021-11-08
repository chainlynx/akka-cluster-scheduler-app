import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

lazy val akkaHttpVersion = "10.2.5"
lazy val akkaVersion     = "2.6.15"
lazy val logbackVersion  = "1.2.3"
lazy val akkaManagementVersion = "1.1.1"
lazy val akkaCassandraVersion  = "1.0.5"
lazy val jacksonVersion  = "3.6.6"
lazy val akkaEnhancementsVersion = "1.1.16"

name := "akka-cluster-scheduler-app"
ThisBuild / version := "0.0.1"
ThisBuild / organization := "com.lightbend.gsa"
ThisBuild / scalaVersion := "2.13.6"
ThisBuild / scalacOptions += "-deprecation"

// We're relying on the new credential file format for lightbend.sbt as described
// here -> https://www.lightbend.com/account/lightbend-platform/credentials, which
// requires a Commercial Lightbend Subscription.
val credentialFile = file("./lightbend.sbt")

val hasCreds = {
    import java.nio.file.Files
    Files.exists(credentialFile.toPath)
  }.booleanValue()

// BEGIN: This requires a Commercial Lightbend Subscription
def commercial : Seq[ModuleID] = {
  Seq(
    Cinnamon.library.cinnamonAkkaHttp,
    Cinnamon.library.cinnamonAkka,
    Cinnamon.library.cinnamonAkkaPersistence,
    Cinnamon.library.cinnamonJvmMetricsProducer,
    Cinnamon.library.cinnamonCHMetrics3,
    Cinnamon.library.cinnamonCHMetricsElasticsearchReporter,
    Cinnamon.library.cinnamonSlf4jEvents,
    Cinnamon.library.cinnamonPrometheus,
    Cinnamon.library.cinnamonPrometheusHttpServer,
    Cinnamon.library.jmxImporter,
    "com.lightbend.akka" %% "akka-diagnostics" % akkaEnhancementsVersion,
  )
}
// END: This requires a Commercial Lightbend Subscription

def oss : Seq[ModuleID] = {
  Seq(
    "com.typesafe.akka" %% "akka-remote" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-cassandra" % akkaCassandraVersion,
    "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
    "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
    "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % akkaManagementVersion,
    "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaManagementVersion,
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagementVersion,
    "org.json4s" %% "json4s-jackson" % jacksonVersion,
    "org.json4s" %% "json4s-core" % jacksonVersion,

    //Logback
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,

    // testing
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
    "commons-io" % "commons-io" % "2.4" % Test,
    "org.scalatest" %% "scalatest" % "3.0.8" % Test
  )
}

def getDockerBaseImage : String = sys.props.get("java.version") match {
  case Some(v) if v.startsWith("11") => "adoptopenjdk/openjdk11"
  case _ => "adoptopenjdk/openjdk8"
}

def dockerSettings = Seq(
  dockerUpdateLatest := true,
  dockerBaseImage := getDockerBaseImage,
  dockerExposedPorts := Seq(8080, 8558, 9200),
  dockerUsername := sys.props.get("docker.username"),
  dockerRepository := sys.props.get("docker.registry")
)

val Telemetry = if(hasCreds){
  println("Found Lightbend Credentials: Including Telemetry and Dependencies")
  Cinnamon
} else {
  println("Lightbend Credentials Not Found")
  Plugins.empty
}

val dependencies = if(hasCreds) commercial ++ oss else oss

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(Telemetry)
  .enablePlugins(MultiJvmPlugin).configs(MultiJvm)
  .enablePlugins(DockerPlugin)
  .settings(multiJvmSettings: _*)
  .settings(
    dockerSettings,
    libraryDependencies ++= dependencies,
    Universal / javaOptions ++= Seq(
      "-Dcom.sun.management.jmxremote.port=8090 -Dcom.sun.management.jmxremote.rmi.port=8090 -Djava.rmi.server.hostname=127.0.0.1 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
    )
  )

run / cinnamon  := true

fork := true