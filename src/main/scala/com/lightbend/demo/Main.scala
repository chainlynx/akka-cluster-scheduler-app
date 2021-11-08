package com.lightbend.demo

import akka.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory

object Main {
  def main(args: Array[String]): Unit = {
    val appConfig = ConfigFactory.load()
    val clusterName = appConfig.getString("clustering.cluster.name")
    val system = ActorSystem[Nothing](ClusterScheduler(), clusterName, appConfig)
    system.whenTerminated
  }
}