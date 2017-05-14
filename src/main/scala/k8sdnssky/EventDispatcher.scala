package k8sdnssky
import java.time.OffsetDateTime

import akka.actor.{Actor, Props}
import akka.event.Logging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import k8sdnssky.EventDispatcher.Protocol._
import k8sdnssky.KubernetesConversions.HasMetadataConvenience

object EventDispatcher {
  object Protocol {
    sealed trait Event
    case class NewRecordEvent(
        resource: HasMetadata,
        hostname: String,
        record: String
    ) extends Event
    case class DeletedRecordEvent(
        resource: HasMetadata,
        hostname: String,
        record: String
    ) extends Event
    case class FailureForRecordEvent(
        resource: HasMetadata,
        hostname: String,
        record: String,
        kind: String,
        reason: String
    ) extends Event
    case class RefreshedRecordsEvent(
        resource: HasMetadata,
        hostname: String
    ) extends Event
  }

  def props(k8s: KubernetesClient): Props = Props(new EventDispatcher(k8s))
}

class EventDispatcher(private val k8s: KubernetesClient) extends Actor {

  val log = Logging(context.system, this)

  private def fireEvent(namespace: String, name: String, evtType: String, reason: String,
      message: String, involvedObject: HasMetadata): Unit = {
    try {
      val event = k8s.events().inNamespace(namespace).withName(name).get()
      k8s.events().inNamespace(namespace).withName(name).edit()
          .withCount(event.getCount + 1).withLastTimestamp(OffsetDateTime.now().toString)
          .done()
    } catch {
      case _: Exception =>
        try {
          k8s.events().createNew()
              .withNewMetadata().withNamespace(namespace).withName(name).endMetadata()
              .withNewInvolvedObject()
              .withApiVersion(involvedObject.getApiVersion).withKind(involvedObject.getKind)
              .withNamespace(involvedObject.namespace).withName(involvedObject.name).withUid(involvedObject.getMetadata.getUid)
              .endInvolvedObject()
              .withNewSource().withComponent("dns-controller").endSource()
              .withType(evtType)
              .withReason(reason)
              .withMessage(message)
              .withFirstTimestamp(OffsetDateTime.now().toString)
              .withLastTimestamp(OffsetDateTime.now().toString)
              .withCount(1)
              .done()
        } catch {
          case e: Exception => log.error(e, "Unable to put event")
        }
    }
  }

  private def truncate(s: String): String = {
    if (s.length > 253) {
      s.substring(0, 253)
    } else {
      s
    }
  }

  override def receive: Receive = {
    case evt: Event => evt match {
      case NewRecordEvent(resource, hostname, record) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.dns.created.$hostname.$record"
        val message = s"DNS record $record created for $hostname"
        fireEvent(resource.namespace, truncate(eventName), "Normal", "RecordCreated", message, resource)
      case DeletedRecordEvent(resource, hostname, record) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.dns.deleted.$hostname.$record"
        val message = s"DNS record $record deleted for $hostname"
        fireEvent(resource.namespace, truncate(eventName), "Normal", "RecordDeleted", message, resource)
      case FailureForRecordEvent(resource, hostname, record, kind, reason) =>
        val failureKind = if (kind != null) kind.toLowerCase else null
        val eventName = List(resource.name, resource.getMetadata.getUid, "dns", "failed", failureKind, hostname, record)
            .filter(_ != null).mkString(".")
        fireEvent(resource.namespace, truncate(eventName), "Warning", "RecordFailure", reason, resource)
      case RefreshedRecordsEvent(resource, hostname) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.dns.refreshed.$hostname"
        val message = s"DNS records refreshed for $hostname"
        fireEvent(resource.namespace, truncate(eventName), "Normal", "RecordsRefreshed", message, resource)
    }
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }
}
