package k8sdnssky
import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import k8sdnssky.AppProperties.DnsProperties
import k8sdnssky.DnsRecordHandler.Protocol.{Refresh, Release, Update}
import k8sdnssky.EventDispatcher.Protocol.{DeletedRecordEvent, FailureForRecordEvent, NewRecordEvent, RefreshedRecordsEvent}
import k8sdnssky.KubernetesConversions.HasMetadataConvenience
import k8sdnssky.SkyDnsRepository._
import k8sdnssky.actuator.DnsInfoContributor.Protocol.{GetInfo, GetInfoResponse}

import scala.language.postfixOps

object DnsRecordHandler {
  object Protocol {
    object Release
    object Refresh
    case class Update(resource: HasMetadata)
  }

  def props(k8s: KubernetesClient, sky: SkyDnsRepository, eventDispatcher: ActorRef, resource: HasMetadata, dnsProperties: DnsProperties): Props = {
    Props(new DnsRecordHandler(k8s, sky, eventDispatcher, resource, dnsProperties))
  }
}

class DnsRecordHandler(
    private val k8s: KubernetesClient,
    private val sky: SkyDnsRepository,
    private val eventDispatcher: ActorRef,
    private val initialResource: HasMetadata,
    private val dnsProperties: DnsProperties) extends Actor {

  private val log = Logging(context.system, this)

  private val whitelist = dnsProperties.whitelistAsList
  private val blacklist = dnsProperties.blacklistAsList

  log.debug(s"Record handler for ${initialResource.asString} with hostnames ${initialResource.hostnames}")
  context.system.eventStream.subscribe(self, classOf[GetInfo])
  updateRecords(initialResource, None)

  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._
  import scala.languageFeature.postfixOps
  private val schedule = context.system.scheduler.schedule(1 minute, 1 minute, self, Refresh)

  context.become(handle(initialResource))

  private def toFailureEvent(resource: HasMetadata, hostname: String, kind: FailureKind): FailureForRecordEvent = {
    kind match {
      case UnableToPut =>
        FailureForRecordEvent(resource, hostname, "default", "UnableToPutInEtcd",
          s"Cannot put DNS record for $hostname in etcd")
      case UnableToDelete =>
        FailureForRecordEvent(resource, hostname, "default", "UnableToDeleteInEtcd",
          s"Cannot delete DNS record for $hostname in etcd")
      case inUse: RecordInUse =>
        if (inUse.record != null) {
          FailureForRecordEvent(resource, hostname, inUse.record, "RecordInUse",
            s"Record ${inUse.record} for hostname $hostname is in use by ${inUse.inUseBy}")
        } else {
          FailureForRecordEvent(resource, hostname, "default", "RecordInUse",
            s"Record for hostname $hostname is in use by ${inUse.inUseBy}")
        }
      case invalidModel: InvalidModel =>
        if (invalidModel.record != null) {
          FailureForRecordEvent(resource, hostname, invalidModel.record, "InvalidModel",
            s"Record ${invalidModel.record} for $hostname has an invalid model in etcd")
        } else {
          FailureForRecordEvent(resource, hostname, "default", "RecordInUse",
            s"Record for $hostname has an invalid model in etcd")
        }
    }
  }

  private def updateHostname(resource: HasMetadata, hostname: String): Unit = {
    val putResponse = sky.put(hostname, resource.loadBalancerIngress.toList,
      resource.getMetadata.getSelfLink, 300)

    if (putResponse.newRecords.nonEmpty) {
      putResponse.newRecords.foreach(record => {
        eventDispatcher ! NewRecordEvent(resource, hostname, record)
      })
      log.debug(s"New records for $hostname: ${putResponse.newRecords.mkString(", ")}")
    }

    if (putResponse.deletedRecords.nonEmpty) {
      putResponse.deletedRecords.foreach(record => {
        eventDispatcher ! DeletedRecordEvent(resource, hostname, record)
      })
      log.debug(s"Deleted records for $hostname: ${putResponse.deletedRecords.mkString(", ")}")
    }

    putResponse.failures.foreach(f => {
      eventDispatcher ! toFailureEvent(resource, hostname, f.kind)
    })
  }

  private def handleResourceUpdate(resource: HasMetadata, previous: HasMetadata): Unit = {
    val toDelete = previous.hostnames.diff(resource.hostnames)
    if (toDelete.nonEmpty) {
      log.debug(s"Removing hosts for ${resource.asString}: $toDelete")
      toDelete.foreach(hostname => {
        val records = resource.loadBalancerIngress.toList
        val deleteFailures = sky.delete(hostname, records)
        records.foreach(record => {
          if (!deleteFailures.contains(record)) {
            eventDispatcher ! DeletedRecordEvent(resource, hostname, record)
          }
        })
        deleteFailures.foreach { case (record, failure) =>
          eventDispatcher ! toFailureEvent(resource, hostname, failure.kind)
          log.error(failure, s"Unable to delete $record for ${resource.asString}")
        }
      })
    }
  }

  private def updateRecords(resource: HasMetadata, previousResource: Option[HasMetadata]): Unit = {
    resource.hostnames.foreach(hostname => {
      val passesWhitelist = if (whitelist.nonEmpty) {
        whitelist.exists(elem => hostname.matches(elem))
      } else {
        true
      }
      val passesBlacklist = if (blacklist.nonEmpty) {
        !blacklist.exists(elem => hostname.matches(elem))
      } else {
        true
      }

      if (!passesWhitelist) {
        val msg = s"Hostname $hostname does not pass DNS policy (not whitelisted)"
        eventDispatcher ! FailureForRecordEvent(resource, hostname, null, "policyfailure", msg)
      } else if (!passesBlacklist) {
        val msg = s"Hostname $hostname does not pass DNS policy (hostname is blacklisted)"
        eventDispatcher ! FailureForRecordEvent(resource, hostname, null, "policyfailure", msg)
      } else {
        updateHostname(resource, hostname)
      }
    })
    previousResource.foreach(previous => {
      handleResourceUpdate(resource, previous)
    })
  }

  def handle(resource: HasMetadata): Receive = {
    case Refresh =>
      updateRecords(resource, None)
    case Release =>
      schedule.cancel()
      log.debug(s"Releasing records for ${resource.asString}")
      resource.hostnames.foreach(hostname => {
        val records = resource.loadBalancerIngress.toList
        val deleteFailures = sky.delete(hostname, records)
        records.foreach(record => {
          if (!deleteFailures.contains(record)) {
            eventDispatcher ! DeletedRecordEvent(resource, hostname, record)
          }
        })
        deleteFailures.foreach(f => {
          eventDispatcher ! toFailureEvent(resource, hostname, f._2.kind)
          log.error(f._2, s"Unable to delete record for hostname ${f._1}")
        })
      })
      log.debug(s"Records for ${resource.asString} released; shutting down")
      context.stop(self)
    case Update(newResource) =>
      updateRecords(newResource, Some(resource))
      context.become(handle(newResource))
    case GetInfo(target) =>
      target ! GetInfoResponse(resource)
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }

  override def receive: Receive = {
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }
}
