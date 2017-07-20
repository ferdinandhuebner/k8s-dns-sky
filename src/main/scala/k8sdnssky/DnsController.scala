package k8sdnssky
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorContext, ActorRef, Kill, Props}
import akka.event.Logging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.{Watch, Watcher}
import k8sdnssky.DnsRecordHandler.Protocol.Release
import k8sdnssky.KubernetesRepository.KubernetesEvent

import scala.collection.mutable
import scala.util.Try

object DnsController {

  val DnsAnnotation = "external-dns.alpha.kubernetes.io/hostname"
  val ControllerAnnotation = "external-dns.alpha.kubernetes.io/controller"

  private[DnsController] object Protocol {
    object Initialize
    object Restart
  }

  def props(
      k8s: KubernetesRepository,
      recordHandlerFactory: (HasMetadata, ActorContext) => ActorRef,
      controllerClass: String
  ): Props =
    Props(new DnsController(k8s, recordHandlerFactory, controllerClass))
}

class DnsController(
    private val k8s: KubernetesRepository,
    private val recordHandlerFactory: (HasMetadata, ActorContext) => ActorRef,
    private val controllerClass: String
) extends Actor {

  private val connectionWasLost = new AtomicBoolean(false)
  val log = Logging(context.system, this)

  import KubernetesConversions._
  import k8sdnssky.DnsController.Protocol._
  import k8sdnssky.DnsController.DnsAnnotation

  initialize()

  private def initialize(): Unit = {
    log.debug("DnsController is initializing")
    val watches: mutable.Buffer[Watch] = mutable.Buffer()
    watches ++= Seq(
      k8s.watchIngresses(evt => self ! evt, e => onConnectionLost(e, watches)),
      k8s.watchServices(evt => self ! evt, e => onConnectionLost(e, watches))
    )
    self ! Initialize
    context.become(initializing(watches.toList, Nil))
  }

  private def onConnectionLost(e: Throwable, watches: Seq[Watch]): Unit = {
    log.debug("Connection to kubernetes lost")
    if (connectionWasLost.compareAndSet(false, true)) {
      log.debug("Telling children to release their records")
      context.children.foreach(actor => actor ! Release)
      watches.foreach(w => Try(w.close()))
      self ! Restart
    }
  }

  private def initializing(watches: Seq[Watch], events: List[KubernetesEvent[_]]): Receive = {

    case evt: KubernetesEvent[_] => context.become(initializing(watches, events :+ evt))

    case Restart => throw new RuntimeException("Restart requested")

    case Initialize =>

      val services = k8s.services()
          .filter(_.annotation(DnsAnnotation) != null)
          .filter(_.hasDnsControllerClass(controllerClass))
          .filter(_.loadBalancerIngress.nonEmpty)
          .map(x => x.hashKey -> x).toMap
      val ingresses = k8s.ingresses()
          .filter(_.hasDnsControllerClass(controllerClass))
          .filter(_.loadBalancerIngress.nonEmpty)
          .map(x => x.hashKey -> x).toMap

      if (events.nonEmpty) {
        log.info(s"Missed ${events.length} events during initialization :(")
      }

      val handlers = (services ++ ingresses).values.map(resource => {
        resource.hashKey -> recordHandlerFactory(resource, context)
      }).toMap

      context.become(watching(watches, services ++ ingresses, handlers))
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }

  sealed trait DnsAction
  object NoChange extends DnsAction
  object IgnoreResource extends DnsAction
  object Put extends DnsAction
  object Delete extends DnsAction
  case class KubernetesAction[T <: HasMetadata](resource: T, action: DnsAction)

  private def actionFor[T <: HasMetadata](evt: KubernetesEvent[T], existing: Option[T]): KubernetesAction[T] = {
    if (evt.action == Watcher.Action.ADDED || evt.action == Watcher.Action.MODIFIED) {
      val isNewHandled = evt.resource.hasDnsControllerClass(controllerClass)
      val wasOldHandled = evt.resource.hasDnsControllerClass(controllerClass)
      val newLb = evt.resource.loadBalancerIngress
      val newHosts = evt.resource.hostnames.toSet
      val existingLb = existing.map(_.loadBalancerIngress).getOrElse(Set.empty)
      val existingHosts = existing.map(_.hostnames).getOrElse(Set.empty)
      if (isNewHandled && !wasOldHandled) {
        log.debug(s"$evt: resource changed from unhandled to handled")
        KubernetesAction(evt.resource, Put)
      } else if (!isNewHandled && !wasOldHandled) {
        log.debug(s"$evt: resource is not relevant")
        KubernetesAction(evt.resource, IgnoreResource)
      } else if (!isNewHandled) {
        log.debug(s"$evt: resource changed from handled to unhandled")
        KubernetesAction(evt.resource, Delete)
      } else if (newLb.nonEmpty) {
        if (newLb != existingLb) {
          log.debug(s"$evt: load balancer changed; put new records")
          KubernetesAction(evt.resource, Put)
        } else {
          if (newHosts != existingHosts) {
            if (newHosts.nonEmpty) {
              log.debug(s"$evt: no load balancer changed but hostnames changed; put new records")
              KubernetesAction(evt.resource, Put)
            } else {
              log.debug(s"$evt: no load balancer changed but no hostnames left; remove records")
              KubernetesAction(evt.resource, Delete)
            }
          } else {
            log.debug(s"$evt: no load balancer change; don't udpate records")
            KubernetesAction(evt.resource, NoChange)
          }
        }
      } else {
        if (existingLb.nonEmpty) {
          log.debug(s"$evt: all load balancers deleted; remove records")
          KubernetesAction(evt.resource, Delete)
        } else {
          log.debug(s"$evt: no load balancers and no change; ignore resource")
          KubernetesAction(evt.resource, IgnoreResource)
        }
      }
    } else if (evt.action == Watcher.Action.DELETED) {
      if (existing.nonEmpty) {
        log.debug(s"$evt: existing load balancers found; remove records")
        KubernetesAction(evt.resource, Delete)
      } else {
        log.debug(s"$evt: no existing load balancers found; ignore resource")
        KubernetesAction(evt.resource, IgnoreResource)
      }
    } else {
      log.debug(s"$evt: no change")
      KubernetesAction(evt.resource, NoChange)
    }
  }

  private def watching(watches: Seq[Watch], resources: Map[String, HasMetadata],
      handlers: Map[String, ActorRef]): Receive = {
    case Restart => throw new RuntimeException("Restart requested")
    case evt: KubernetesEvent[_] =>
      val action = evt.action
      val resource = evt.resource

      log.debug(s"$action ${resource.asString}")
      val dnsAction = actionFor(evt, resources.get(resource.hashKey))
      log.debug(s"Action for ${resource.asString}: ${dnsAction.action}")

      dnsAction.action match {
        case NoChange =>
          context.become(watching(watches, resources + (resource.hashKey -> resource), handlers))
        case Put =>
          val newHandler = handlers.get(resource.hashKey).map(handler => {
            handler ! DnsRecordHandler.Protocol.Update(resource)
            handler
          }).getOrElse(recordHandlerFactory(resource, context))

          val newHandlers = handlers + (resource.hashKey -> newHandler)
          context.become(watching(watches, resources + (resource.hashKey -> resource), newHandlers))
        case Delete =>
          handlers.get(resource.hashKey).foreach(handler => {
            handler ! DnsRecordHandler.Protocol.Release
          })
          context.become(watching(watches, resources - resource.hashKey, handlers - resource.hashKey))
        case IgnoreResource =>
      }
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }

  override def receive: Receive = {
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }
  override protected[akka] def aroundPostRestart(reason: Throwable): Unit = {
    log.debug("DnsController restarted")
    super.aroundPostRestart(reason)
  }
}
