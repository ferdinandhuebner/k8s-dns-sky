package k8sdnssky.actuator
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS

import akka.actor.{ActorRef, ActorSystem, Props}
import com.google.common.base.Stopwatch
import io.fabric8.kubernetes.client.KubernetesClient
import k8sdnssky.EtcdProperties
import mousio.etcd4j.EtcdClient
import org.springframework.beans.factory.annotation.{Autowired, Qualifier}
import org.springframework.boot.actuate.health.{Health, HealthIndicator}
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.context.annotation.{Bean, Configuration}

import scala.util.Try

@Configuration
class ActuatorConfig {

  @Autowired
  private var actorSystem: ActorSystem = _

  @Autowired
  private var kubernetesClient: KubernetesClient = _

  @Autowired
  private var etcdClient: EtcdClient = _

  @Autowired
  private var etcdProperties: EtcdProperties = _

  @Bean
  def dnsInfoContributor(): ActorRef = {
    actorSystem.actorOf(Props(new DnsInfoContributor()))
  }

  @Bean
  def dnsInfoContributorAdapter(): InfoContributor = {
    new DnsInfoContributorAdapter(dnsInfoContributor())
  }

  @Bean
  def kubernetesHealthIndicator: HealthIndicator = () => {
    try {
      val watch = Stopwatch.createStarted()
      kubernetesClient.services().inNamespace("default").withName("kubernetes").get()
      watch.stop()
      Health.up()
          .withDetail("get", "GET Service kubernetes@default in " + watch.toString)
          .build()
    } catch {
      case e: Exception =>
        Health.down(e).build()
    }
  }

  @Bean
  def etcdHealthIndicator(): HealthIndicator = new HealthIndicator {
    private val timeout = etcdProperties.timeout
    private val etcd = etcdClient
    private val id = UUID.randomUUID().toString
    override def health(): Health = {
      val value = UUID.randomUUID().toString
      val put = Stopwatch.createUnstarted()
      val get = Stopwatch.createUnstarted()
      try {
        put.start()
        etcd.put(s"/health-$id", value).ttl(timeout * 2).timeout(timeout, SECONDS).send().get()
        put.stop()
        get.start()
        val response = etcd.get(s"/health-$id").timeout(timeout, SECONDS).send().get()
        get.stop()
        if (response.getNode.getValue == value) {
          Health.up().withDetail("put", put.toString).withDetail("get", get.toString).build()
        } else {
          Health.down().withDetail("put", put.toString).withDetail("get", get.toString)
              .withDetail("reason", "put and get failed; unexpected value").build()
        }
      } catch {
        case e: Exception =>
          Health.down(e).build()
      } finally {
        Try(put.stop())
        Try(get.stop())
      }
    }
  }
}
