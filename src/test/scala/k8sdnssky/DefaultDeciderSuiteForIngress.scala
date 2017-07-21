package k8sdnssky
import io.fabric8.kubernetes.api.model.{Service, ServiceBuilder}
import io.fabric8.kubernetes.api.model.extensions.{Ingress, IngressBuilder}
import io.fabric8.kubernetes.client.Watcher
import k8sdnssky.Decider.{Delete, IgnoreResource, NoChange, Put}
import k8sdnssky.KubernetesRepository.{IngressEvent, ServiceEvent}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpecLike, MustMatchers}

class DefaultDeciderSuiteForIngress extends LogbackInitializer with FlatSpecLike with MustMatchers
    with MockitoSugar {

  private val decider = new DefaultDecider()

  private def newIngress(name: String, namespace: String, hostnames: List[String], ips: List[String]): Ingress = {
    val b = new IngressBuilder() //
        .withNewMetadata().withName(name).withNamespace(namespace).endMetadata() //

    val spec = b.withNewSpec() //
    hostnames.foreach(hostname => {
      spec.addNewRule().withHost(hostname).endRule()
    })
    spec.endSpec()

    val status = b.withNewStatus()
    ips.foreach(ip => {
      status.withNewLoadBalancer().addNewIngress().withIp(ip).endIngress().endLoadBalancer()
    })
    status.endStatus()

    b.build()
  }

  "for ingresses, the default decider" must "put if (some host, some lb)" in {
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe Put
  }

  it must "put if (some hosts, some lb)" in {
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe Put
  }

  it must "put if (some host, some lbs)" in {
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe Put
  }

  it must "put if (some hosts, some lbs)" in {
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe Put
  }

  it must "ignore if (some host, no lb)" in {
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some hosts, no lb)" in {
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb)" in {
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lbs)" in {
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb)" in {
    val ing = newIngress("name", "namespace", Nil, Nil)
    val evt = IngressEvent(ing, Watcher.Action.ADDED)

    val action = decider.actionFor(evt, None, null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (no host, no lb)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", Nil, Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (no host, some lb)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (no host, some lbs)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (some host, no lb)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, no lb) -> (some hosts, no lb)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "put if (no host, no lb) -> (some host, some lb)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, no lb) -> (some hosts, some lb)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, no lb) -> (some host, some lbs)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, no lb) -> (some hosts, some lbs)" in {
    val old = newIngress("name", "namespace", Nil, Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "ignore if (some host, no lb) -> (no host, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", Nil, Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (some host, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (another host, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", List("bar.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (some hosts, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (no host, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> (no host, some lbs)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "put if (some host, no lb) -> (some host, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, no lb) -> (another host, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", List("bar.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, no lb) -> (some hosts, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp","bar.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, no lb) -> (some hosts, some lbs)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val ing = newIngress("name", "namespace", List("foo.acme.corp","bar.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "ignore if (no host, some lb) -> (no host, no lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (some host, no lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (some hosts, no lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (no host, some lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (no host, some lbs)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> (no host, another lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, List("2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe IgnoreResource
  }

  it must "put if (no host, some lb) -> (some host, some lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, some lb) -> (some host, another lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, some lb) -> (some hosts, some lb)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, some lb) -> (some host, some lbs)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (no host, some lb) -> (some hosts, some lbs)" in {
    val old = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "delete if (some host, some lb) -> (no host, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (some host, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (some hosts, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (another host, no lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("bar.acme.corp"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (no host, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (no host, some lbs)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "delete if (some host, some lb) -> (no host, another lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", Nil, List("2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Delete
  }

  it must "no change if (some host, some lb) -> (some host, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe NoChange
  }

  it must "put if (some host, some lb) -> (another host, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("bar.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (some host, another lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp"), List("2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (another host, another lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("bar.acme.corp"), List("2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (some hosts, some lb)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (some host, some lbs)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("bar.acme.corp"), List("1.2.3.4", "2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "put if (some host, some lb) -> (some hosts, some lbs)" in {
    val old = newIngress("name", "namespace", List("foo.acme.corp"), List("1.2.3.4"))
    val ing = newIngress("name", "namespace", List("foo.acme.corp", "bar.acme.corp"), List("1.2.3.4","2.3.4.5"))
    val evt = IngressEvent(ing, Watcher.Action.MODIFIED)

    val action = decider.actionFor(evt, Some(old), null)
    action.action mustBe Put
  }

  it must "ignore if (no host, no lb) -> deleted" in {
    val ing = newIngress("name", "namespace", Nil, Nil)
    val evt = IngressEvent(ing, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(ing), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (some host, no lb) -> deleted" in {
    val ing = newIngress("name", "namespace", List("foo.bar.com"), Nil)
    val evt = IngressEvent(ing, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(ing), null)
    action.action mustBe IgnoreResource
  }

  it must "ignore if (no host, some lb) -> deleted" in {
    val ing = newIngress("name", "namespace", Nil, List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(ing), null)
    action.action mustBe IgnoreResource
  }

  it must "delete if (some host, some lb) -> deleted" in {
    val ing = newIngress("name", "namespace", List("foo.bar.com"), List("1.2.3.4"))
    val evt = IngressEvent(ing, Watcher.Action.DELETED)

    val action = decider.actionFor(evt, Some(ing), null)
    action.action mustBe Delete
  }

}
