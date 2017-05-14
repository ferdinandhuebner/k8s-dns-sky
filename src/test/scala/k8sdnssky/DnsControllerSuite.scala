package k8sdnssky
import akka.actor.{ActorContext, ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.extensions.IngressBuilder
import io.fabric8.kubernetes.client.Watcher.Action
import k8sdnssky.DnsRecordHandler.Protocol.{Release, Update}
import k8sdnssky.KubernetesRepository.IngressEvent
import org.assertj.core.api.Assertions._
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, MustMatchers}

class DnsControllerSuite extends TestKit(ActorSystem("dns-controller-suite"))
    with LogbackInitializer
    with ImplicitSender with FlatSpecLike with MustMatchers
    with MockitoSugar with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "a dns-controller" must "create a new record handler for new records" in {
    val k8s = new KubernetesRepositoryStub(Nil, Nil)
    val factory: ((HasMetadata, ActorContext) => ActorRef) = mock[(HasMetadata, ActorContext) => ActorRef]
    val mockedHandler = mock[ActorRef]
    when(factory.apply(any(), any())).thenReturn(mockedHandler)
    val props = DnsController.props(k8s, factory, null)

    val actor = system.actorOf(props)

    val ing = new IngressBuilder()
        .withNewMetadata().withName("test").withNamespace("default").endMetadata()
        .withNewSpec().addNewRule().withHost("test-host").endRule().endSpec()
        .withNewStatus().withNewLoadBalancer()
        .addNewIngress().withIp("192.168.1.100").endIngress()
        .endLoadBalancer().endStatus().build()

    val evt = IngressEvent(ing, Action.ADDED)
    k8s.fireIngEvent(evt)

    verify(factory, timeout(500)).apply(ArgumentMatchers.eq(ing), any())
    system.stop(actor)
  }

  it must "tell the record handler to update the resource after a change" in {
    val k8s = new KubernetesRepositoryStub(Nil, Nil)
    val factory: ((HasMetadata, ActorContext) => ActorRef) = mock[(HasMetadata, ActorContext) => ActorRef]

    val mockedHandler = TestProbe()

    when(factory.apply(any(), any())).thenReturn(mockedHandler.ref)
    val props = DnsController.props(k8s, factory, null)

    val actor = system.actorOf(props)

    val ing = new IngressBuilder()
        .withNewMetadata().withName("test").withNamespace("default").endMetadata()
        .withNewSpec().addNewRule().withHost("test-host").endRule().endSpec()
        .withNewStatus().withNewLoadBalancer()
        .addNewIngress().withIp("192.168.1.100").endIngress()
        .endLoadBalancer().endStatus().build()

    val evt = IngressEvent(ing, Action.ADDED)
    k8s.fireIngEvent(evt)

    verify(factory, timeout(500)).apply(ArgumentMatchers.eq(ing), any())

    val updatedIngress = new IngressBuilder(ing).editStatus()
        .withNewLoadBalancer()
        .addNewIngress().withIp("192.168.1.101").endIngress()
        .endLoadBalancer().endStatus().build()

    k8s.fireIngEvent(IngressEvent(updatedIngress, Action.MODIFIED))
    val update = mockedHandler.expectMsgClass(classOf[Update])
    assertThat(update.resource).isEqualTo(updatedIngress)

    system.stop(mockedHandler.ref)
    system.stop(actor)
  }

  it must "tell the record handler to release the resource if it is no longer relevant" in {
    val k8s = new KubernetesRepositoryStub(Nil, Nil)
    val factory: ((HasMetadata, ActorContext) => ActorRef) = mock[(HasMetadata, ActorContext) => ActorRef]

    val mockedHandler = TestProbe()

    when(factory.apply(any(), any())).thenReturn(mockedHandler.ref)
    val props = DnsController.props(k8s, factory, null)

    val actor = system.actorOf(props)

    val ing = new IngressBuilder()
        .withNewMetadata().withName("test").withNamespace("default").endMetadata()
        .withNewSpec().addNewRule().withHost("test-host").endRule().endSpec()
        .withNewStatus().withNewLoadBalancer()
        .addNewIngress().withIp("192.168.1.100").endIngress()
        .endLoadBalancer().endStatus().build()

    val evt = IngressEvent(ing, Action.ADDED)
    k8s.fireIngEvent(evt)

    verify(factory, timeout(500)).apply(ArgumentMatchers.eq(ing), any())

    val updatedIngress = new IngressBuilder(ing).editStatus()
        .withNewLoadBalancer()
        .endLoadBalancer().endStatus().build()

    k8s.fireIngEvent(IngressEvent(updatedIngress, Action.MODIFIED))
    mockedHandler.expectMsg(Release)

    system.stop(mockedHandler.ref)
    system.stop(actor)
  }
}
