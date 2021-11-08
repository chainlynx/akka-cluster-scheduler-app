package com.lightbend.demo

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.Cluster
import akka.http.scaladsl.Http
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import com.lightbend.demo.ScheduleEntity.{ScheduleCommand, ScheduleEntityShardName}
import akka.{actor => orig}
import com.lightbend.demo.Protocol.ScheduleId

object ClusterScheduler {

  def apply() =
    Behaviors.setup[Nothing] { context =>

      context.log.info("Scheduler Application Starting...")

      implicit val classic: orig.ActorSystem = context.system.toClassic

      val config = context.system.settings.config

      val TypeKey = EntityTypeKey[ScheduleCommand](ScheduleEntityShardName)
      val self = Cluster(context.system).selfMember
      val sharding = ClusterSharding(context.system)

      context.log.info(s"Starting node with roles: ${self.roles}")

      if(self.hasRole("k8s")) {
        AkkaManagement(classic).start()
        ClusterBootstrap(classic).start()
      }

      if(self.hasRole("backend")){
        sharding.init(
          Entity(TypeKey)(createBehavior = ctx => ScheduleEntity(ScheduleId(ctx.entityId)))
            .withSettings(ClusterShardingSettings(context.system).withRole("backend")))
      }
      else if(self.hasRole("frontend")) {
        implicit val ec = context.system.executionContext
        val entityRef: ActorRef[ShardingEnvelope[ScheduleCommand]] =
          sharding.init(Entity(TypeKey)(createBehavior = ctx => ScheduleEntity(ScheduleId(ctx.entityId))))

        val httpPort = config.getInt("akka.http.server.default-http-port")
        val interface = if (!self.hasRole("docker") && !self.hasRole("k8s")) "localhost" else "0.0.0.0"
        val binding = Http().newServerAt(interface, httpPort)
          .bind(new SchedulerRoutes(context.system, entityRef).routes)

        binding.foreach { binding =>
          context.log.info(s"Server online inside container on ip: ${binding.localAddress} port $httpPort")
        }
      }

      Behaviors.empty
    }
}
