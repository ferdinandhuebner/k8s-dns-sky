package k8sdnssky
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.extensions.Ingress
import io.fabric8.kubernetes.client.Watcher.Action
import io.fabric8.kubernetes.client.{KubernetesClientException, Watch}
import k8sdnssky.KubernetesConversions.HasMetadataConvenience
import k8sdnssky.KubernetesRepository.KubernetesEvent

import scala.collection.mutable

class KubernetesRepositoryStub(
    private val svcList: List[Service],
    private val ingList: List[Ingress]
) extends KubernetesRepository {

  private val svcs: mutable.Map[String, Service] = mutable.Map()
  private val ings: mutable.Map[String, Ingress] = mutable.Map()
  private val svcWatches: mutable.Buffer[(KubernetesEvent[Service]) => Unit] = mutable.Buffer()
  private val ingWatches: mutable.Buffer[(KubernetesEvent[Ingress]) => Unit] = mutable.Buffer()

  override def services(): List[Service] = svcs.values.toList
  override def ingresses(): List[Ingress] = ings.values.toList

  def reset(): Unit = {
    svcs.clear()
    ings.clear()
    svcWatches.clear()
    ingWatches.clear()
  }

  def fireSvcEvent(evt: KubernetesEvent[Service]): Unit = {
    evt.action match {
      case Action.ADDED =>
        svcs += evt.resource.hashKey -> evt.resource
        svcWatches.foreach(_.apply(evt))
      case Action.MODIFIED =>
        svcs += evt.resource.hashKey -> evt.resource
        svcWatches.foreach(_.apply(evt))
      case Action.DELETED =>
        svcs -= evt.resource.hashKey
        svcWatches.foreach(_.apply(evt))
      case Action.ERROR =>
    }
  }
  def fireIngEvent(evt: KubernetesEvent[Ingress]): Unit = {
    evt.action match {
      case Action.ADDED =>
        ings += evt.resource.hashKey -> evt.resource
        ingWatches.foreach(_.apply(evt))
      case Action.MODIFIED =>
        ings += evt.resource.hashKey -> evt.resource
        ingWatches.foreach(_.apply(evt))
      case Action.DELETED =>
        ings -= evt.resource.hashKey
        ingWatches.foreach(_.apply(evt))
      case Action.ERROR =>
    }
  }

  override def watchServices(
      watcher: (KubernetesEvent[Service]) => Unit,
      onCloseFunction: (KubernetesClientException) => Unit): Watch = {
    svcWatches += watcher
    () => {}
  }

  override def watchIngresses(
      watcher: (KubernetesEvent[Ingress]) => Unit,
      onCloseFunction: (KubernetesClientException) => Unit): Watch = {
    ingWatches += watcher
    () => {}
  }
}
