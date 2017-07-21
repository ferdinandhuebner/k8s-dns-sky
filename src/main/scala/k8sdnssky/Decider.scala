package k8sdnssky
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.Watcher
import k8sdnssky.Decider.KubernetesAction
import k8sdnssky.KubernetesRepository.KubernetesEvent
import org.slf4j.{Logger, LoggerFactory}

object Decider {

  sealed trait DnsAction
  object NoChange extends DnsAction
  object IgnoreResource extends DnsAction
  object Put extends DnsAction
  object Delete extends DnsAction

  case class KubernetesAction[T <: HasMetadata](resource: T, action: DnsAction)
}

trait Decider {

  def actionFor[T <: HasMetadata](evt: KubernetesEvent[T], existing: Option[T], controllerClass: String): KubernetesAction[T]
}

class DefaultDecider extends Decider {

  import KubernetesConversions._
  import Decider.{NoChange, IgnoreResource, Put, Delete}

  private val log: Logger = LoggerFactory.getLogger(classOf[DefaultDecider])

  //noinspection MapGetOrElseBoolean
  override def actionFor[T <: HasMetadata](
      evt: KubernetesEvent[T],
      existing: Option[T],
      controllerClass: String): KubernetesAction[T] = {

    if (evt.action == Watcher.Action.ADDED || evt.action == Watcher.Action.MODIFIED) {
      val hasControllerClass = evt.resource.hasDnsControllerClass(controllerClass)
      val hadControllerClass = existing.map(_.hasDnsControllerClass(controllerClass)).getOrElse(false)
      val newLb = evt.resource.loadBalancerIngress
      val newHosts = evt.resource.hostnames.toSet
      val existingLb = existing.map(_.loadBalancerIngress).getOrElse(Set.empty)
      val existingHosts = existing.map(_.hostnames.toSet).getOrElse(Set.empty)

      val needToHandle = hasControllerClass && newLb.nonEmpty && newHosts.nonEmpty
      val wasHandled = hadControllerClass && existingLb.nonEmpty && existingHosts.nonEmpty

      if (!needToHandle && !wasHandled) {
        log.debug(s"$evt: resource is not relevant")
        KubernetesAction(evt.resource, IgnoreResource)
      } else if (!needToHandle && wasHandled) {
        log.debug(s"$evt: resource changed from handled to unhandled")
        KubernetesAction(evt.resource, Delete)
      } else if (needToHandle && !wasHandled) {
        log.debug(s"$evt: resource wasn't handled before but needs to be now")
        KubernetesAction(evt.resource, Put)
      } else {
        // need to handle the resource and it was handled before
        if (newLb != existingLb) {
          if (newHosts != existingHosts) {
            log.debug(s"$evt: load balancer changed and hostnames changed; put new records")
            KubernetesAction(evt.resource, Put)
          } else {
            log.debug(s"$evt: load balancer changed, hostnames did not; put new records")
            KubernetesAction(evt.resource, Put)
          }
        } else {
          if (newHosts != existingHosts) {
            log.debug(s"$evt: load balancer did not change but hostnames did; put new records")
            KubernetesAction(evt.resource, Put)
          } else {
            log.debug(s"$evt: load balancer and hostnames did not change")
            KubernetesAction(evt.resource, NoChange)
          }
        }
      }
    } else if (evt.action == Watcher.Action.DELETED) {
      val hasControllerClass = evt.resource.hasDnsControllerClass(controllerClass)
      val hadLb = evt.resource.loadBalancerIngress.nonEmpty
      val hadHosts = evt.resource.hostnames.toSet.nonEmpty

      if (hasControllerClass && hadLb && hadHosts) {
        log.debug(s"$evt: existing load balancers and dns names found; remove records")
        KubernetesAction(evt.resource, Delete)
      } else {
        log.debug(s"$evt: resource was not relevant")
        KubernetesAction(evt.resource, IgnoreResource)
      }
    } else {
      log.debug(s"$evt: no change")
      KubernetesAction(evt.resource, NoChange)
    }
  }
}