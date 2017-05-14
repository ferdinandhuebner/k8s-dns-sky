package k8sdnssky
import io.fabric8.kubernetes.api.model.{HasMetadata, Service}
import io.fabric8.kubernetes.api.model.extensions.Ingress
import io.fabric8.kubernetes.client._
import k8sdnssky.KubernetesRepository.{IngressEvent, KubernetesEvent, ServiceEvent}
import okhttp3.Request

object KubernetesRepository {
  private[k8sdnssky] sealed trait KubernetesEvent[+T <: HasMetadata] {
    def resource: T
    def action: Watcher.Action

    override def toString: String = {
      import KubernetesConversions.HasMetadataConvenience
      s"${resource.getKind} ${resource.name}@${resource.namespace} $action"
    }
  }

  private[k8sdnssky] case class ServiceEvent(resource: Service, action: Watcher.Action)
      extends KubernetesEvent[Service]
  private[k8sdnssky] case class IngressEvent(resource: Ingress, action: Watcher.Action)
      extends KubernetesEvent[Ingress]

  private[k8sdnssky] object Protocol {
    object Initialize
  }
}

trait KubernetesRepository {

  def services(): List[Service]
  def ingresses(): List[Ingress]
  def watchServices(watcher: KubernetesEvent[Service] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch
  def watchIngresses(watcher: KubernetesEvent[Ingress] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch
}

class KubernetesRepositoryImpl(private val kubernetesClient: KubernetesClient) extends KubernetesRepository {

  import scala.collection.JavaConverters._

  def services(): List[Service] = {
    iterableAsScalaIterable(
      kubernetesClient.services().inAnyNamespace().list().getItems
    ).toList
  }

  def ingresses(): List[Ingress] = {
    iterableAsScalaIterable(
      kubernetesClient.extensions().ingresses().inAnyNamespace().list().getItems
    ).toList
  }

  def watchServices(watcher: KubernetesEvent[Service] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch = {

    kubernetesClient.services().inAnyNamespace().watch(new Watcher[Service] {
      override def onClose(cause: KubernetesClientException): Unit = {
        onCloseFunction.apply(cause)
      }
      override def eventReceived(action: Watcher.Action, resource: Service): Unit = {
        watcher.apply(ServiceEvent(resource, action))
      }
    })
  }

  def watchIngresses(watcher: KubernetesEvent[Ingress] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch = {
    kubernetesClient.extensions().ingresses().inAnyNamespace().watch(new Watcher[Ingress] {
      override def onClose(cause: KubernetesClientException): Unit = {
        onCloseFunction.apply(cause)
      }
      override def eventReceived(action: Watcher.Action, resource: Ingress): Unit = {
        watcher.apply(IngressEvent(resource, action))
      }
    })
  }
}
