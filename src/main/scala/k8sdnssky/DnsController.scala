package k8sdnssky
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorContext, ActorRef, Props}
import akka.event.Logging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watch
import k8sdnssky.Decider.{Delete, IgnoreResource, NoChange, Put}
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
      decider: Decider,
      controllerClass: String
  ): Props =
    Props(new DnsController(k8s, recordHandlerFactory, decider, controllerClass))
}

class DnsController(
    private val k8s: KubernetesRepository,
    private val recordHandlerFactory: (HasMetadata, ActorContext) => ActorRef,
    private val decider: Decider,
    private val controllerClass: String
) extends Actor {

  private val connectionWasLost = new AtomicBoolean(false)
  val log = Logging(context.system, this)

  import KubernetesConversions._
  import k8sdnssky.DnsController.DnsAnnotation
  import k8sdnssky.DnsController.Protocol._

  private val watches: List[Watch] = initialize()

  private def initialize(): List[Watch] = {
    log.debug("DnsController is initializing")
    val watches: mutable.Buffer[Watch] = mutable.Buffer()
    try {
      watches ++= Seq(
        k8s.watchIngresses(evt => self ! evt, e => onConnectionLost(e)),
        k8s.watchServices(evt => self ! evt, e => onConnectionLost(e))
      )
      val services = k8s.services()
          .filter(_.annotation(DnsAnnotation) != null)
          .filter(_.hasDnsControllerClass(controllerClass))
          .filter(_.loadBalancerIngress.nonEmpty)
          .map(x => x.hashKey -> x).toMap
      val ingresses = k8s.ingresses()
          .filter(_.hasDnsControllerClass(controllerClass))
          .filter(_.loadBalancerIngress.nonEmpty)
          .map(x => x.hashKey -> x).toMap

      val handlers = (services ++ ingresses).values.map(resource => {
        resource.hashKey -> recordHandlerFactory(resource, context)
      }).toMap

      context.become(watching(services ++ ingresses, handlers))
      watches.toList
    } catch {
      case e: Throwable =>
        watches.foreach(w => Try(w.close()))
        throw e
    }
  }

  private def onConnectionLost(e: Throwable): Unit = {
    log.debug("Connection to kubernetes lost")
    if (connectionWasLost.compareAndSet(false, true)) {
      self ! Restart
    }
  }

  override def postStop(): Unit = {
    log.debug("closing watches to kubernetes")
    watches.foreach(w => Try(w.close()))
    super.postStop()
  }

  private def watching(resources: Map[String, HasMetadata],
      handlers: Map[String, ActorRef]): Receive = {
    case Restart =>
      throw new RuntimeException("Restart requested")
    case evt: KubernetesEvent[_] =>
      val action = evt.action
      val resource = evt.resource

      log.debug(s"$action ${resource.asString}")
      val dnsAction = decider.actionFor(evt, resources.get(resource.hashKey), controllerClass)
      log.debug(s"Action for ${resource.asString}: ${dnsAction.action}")

      dnsAction.action match {
        case NoChange =>
          context.become(watching(resources + (resource.hashKey -> resource), handlers))
        case Put =>
          val newHandler = handlers.get(resource.hashKey).map(handler => {
            handler ! DnsRecordHandler.Protocol.Update(resource)
            handler
          }).getOrElse(recordHandlerFactory(resource, context))

          val newHandlers = handlers + (resource.hashKey -> newHandler)
          context.become(watching(resources + (resource.hashKey -> resource), newHandlers))
        case Delete =>
          handlers.get(resource.hashKey).foreach(handler => {
            handler ! DnsRecordHandler.Protocol.Release
          })
          context.become(watching(resources - resource.hashKey, handlers - resource.hashKey))
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
}
