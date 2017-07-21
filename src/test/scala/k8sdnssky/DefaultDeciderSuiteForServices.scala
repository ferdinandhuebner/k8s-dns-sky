package k8sdnssky

import io.fabric8.kubernetes.api.model.extensions.{Ingress, IngressBuilder}
import io.fabric8.kubernetes.api.model.{Service, ServiceBuilder}
import io.fabric8.kubernetes.client.Watcher
import k8sdnssky.Decider.{Delete, IgnoreResource, NoChange, Put}
import k8sdnssky.KubernetesRepository.{IngressEvent, ServiceEvent}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpecLike, MustMatchers}

class DefaultDeciderSuiteForServices extends LogbackInitializer with FlatSpecLike with MustMatchers
    with MockitoSugar {

  private val decider = new DefaultDecider()

  private def newService(name: String, namespace: String, hostname: Option[String], ips: List[String]): Service = {
    val meta = new ServiceBuilder()
        .withNewMetadata().withName(name).withNamespace(namespace)

    hostname.map(meta.addToAnnotations(DnsController.DnsAnnotation, _))
    val s = meta.endMetadata()


    val status = s.withNewStatus()
    ips.foreach(ip => {
      status.withNewLoadBalancer().addNewIngress().withIp(ip).endIngress().endLoadBalancer()
    })
    status.endStatus()

    s.build()
  }

  "for services, the default decider" must "put if (some host, some lb)" in {
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe Put
  }

  it must "put if (some host, some lbs)" in {
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe Put
  }

  it must "ignore if (some host, no lb)" in {
    val svc = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb)" in {
    val svc = newService("name", "namespace", None, List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lbs)" in {
    val svc = newService("name", "namespace", None, List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb)" in {
    val svc = newService("name", "namespace", None, Nil)
    val evt = ServiceEvent(svc, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (no host, no lb)" in {
    val old = newService("name", "namespace", None, Nil)
    val svc = newService("name", "namespace", None, Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (no host, some lb)" in {
    val old = newService("name", "namespace", None, Nil)
    val svc = newService("name", "namespace", None, List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (no host, some lbs)" in {
    val old = newService("name", "namespace", None, Nil)
    val svc = newService("name", "namespace", None, List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (some host, no lb)" in {
    val old = newService("name", "namespace", None, Nil)
    val svc = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "put if (no host, no lb) -> (some host, some lb)" in {
    val old = newService("name", "namespace", None, Nil)
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, no lb) -> (some host, some lbs)" in {
    val old = newService("name", "namespace", None, Nil)
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "ignore if (some host, no lb) -> (no host, no lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val svc = newService("name", "namespace", None, Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (some host, no lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val svc = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (another host, no lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val svc = newService("name", "namespace", Some("bar.acme.corp"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (no host, some lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val svc = newService("name", "namespace", None, List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (no host, some lbs)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val svc = newService("name", "namespace", None, List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "put if (some host, no lb) -> (some host, some lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, no lb) -> (another host, some lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val svc = newService("name", "namespace", Some("bar.acme.corp"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "ignore if (no host, some lb) -> (no host, no lb)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", None, Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (some host, no lb)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (no host, some lb)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", None, List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (no host, some lbs)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", None, List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (no host, another lb)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", None, List("2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "put if (no host, some lb) -> (some host, some lb)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, some lb) -> (some host, another lb)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, some lb) -> (some host, some lbs)" in {
    val old = newService("name", "namespace", None, List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "delete if (some host, some lb) -> (no host, no lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", None, Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (some host, no lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("foo.acme.corp"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (another host, no lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("bar.acme.corp"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (no host, some lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", None, List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (no host, some lbs)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", None, List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (no host, another lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", None, List("2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "no change if (some host, some lb) -> (some host, some lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe NoChange
  }

  it must "put if (some host, some lb) -> (another host, some lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("bar.acme.corp"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (some host, another lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("foo.acme.corp"), List("2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (another host, another lb)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("bar.acme.corp"), List("2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (some host, some lbs)" in {
    val old = newService("name", "namespace", Some("foo.acme.corp"), List("1.2.3.4"))
    val svc = newService("name", "namespace", Some("bar.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = ServiceEvent(svc, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "ignore if (no host, no lb) -> deleted" in {
    val svc = newService("name", "namespace", None, Nil)
    val evt = ServiceEvent(svc, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(svc), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> deleted" in {
    val svc = newService("name", "namespace", Some("foo.bar.com"), Nil)
    val evt = ServiceEvent(svc, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(svc), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> deleted" in {
    val svc = newService("name", "namespace", None, List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(svc), null)
    action.action mustBe IgnoreResource
  }

  it must "delete if (some host, some lb) -> deleted" in {
    val svc = newService("name", "namespace", Some("foo.bar.com"), List("1.2.3.4"))
    val evt = ServiceEvent(svc, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(svc), null)
    action.action mustBe Delete
  }

}
