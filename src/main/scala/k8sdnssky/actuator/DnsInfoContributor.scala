package k8sdnssky.actuator
import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.util.Timeout
import io.fabric8.kubernetes.api.model.HasMetadata
import k8sdnssky.KubernetesConversions.HasMetadataConvenience
import k8sdnssky.actuator.DnsInfoContributor.Protocol.{GetInfo, GetInfoResponse, UpdateInfo}
import org.springframework.boot.actuate.info.{Info, InfoContributor}

import scala.collection.mutable
import scala.concurrent.Await

object DnsInfoContributor {
  object Protocol {
    case class GetInfo(target: ActorRef)
    object UpdateInfo
    case class GetInfoResponse(resource: HasMetadata)
  }
}

class DnsInfoContributorAdapter(actor: ActorRef) extends InfoContributor {

  import akka.pattern.ask

  override def contribute(builder: Info.Builder): Unit = {
    import scala.concurrent.duration._
    import scala.languageFeature.postfixOps

    implicit val timeout = Timeout(5 seconds)
    val future = actor ? GetInfo(ActorRef.noSender)
    Await.result(future, timeout.duration) match {
      case resources: java.util.List[_] => builder.withDetail("dnsManagedResources", resources)
    }
  }
}

class DnsInfoContributor extends Actor {

  val log = Logging(context.system, this)

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._
  import scala.languageFeature.postfixOps
  private val schedule = context.system.scheduler.schedule(1 minute, 1 minute, self, UpdateInfo)

  private val resources: mutable.Buffer[HasMetadata] = mutable.Buffer()

  self ! UpdateInfo

  override def receive: Receive = {
    case UpdateInfo =>
      context.system.eventStream.publish(GetInfo(self))
      resources.clear()
    case GetInfo(target) =>
      import scala.collection.JavaConverters._
      val response = resources.map(res => {
        Map("resource" ->
            Map(
              "name" -> res.asString,
              "hostnames" -> res.hostnames.asJava
            ).asJava
        ).asJava
      }).asJava

      if (target == ActorRef.noSender) {
        sender ! response
      } else {
        target ! response
      }
    case GetInfoResponse(resource) =>
      resources += resource
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }
}
