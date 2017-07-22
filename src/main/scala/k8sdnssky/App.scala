package k8sdnssky

import java.util.Properties
import java.util.concurrent.TimeUnit

import akka.actor.{ActorContext, ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import akka.pattern.{Backoff, BackoffSupervisor}
import com.typesafe.config.ConfigFactory
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.{DefaultKubernetesClient, KubernetesClient}
import k8sdnssky.AppProperties.{DnsProperties, KubernetesProperties}
import mousio.etcd4j.EtcdClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.actuate.info.{Info, InfoContributor}
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.{Bean, Configuration}

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

object App {

  def main(args: Array[String]): Unit = {
    val app = new SpringApplication(classOf[AppConfig])
    app.setDefaultProperties(loadDefaultProperties())
    app.run(args: _*)
  }

  private def loadDefaultProperties(): Properties = {
    val props = new Properties()
    val stream = getClass.getResourceAsStream("/app-defaults.properties")
    try {
      props.load(stream)
      props
    } finally {
      stream.close()
    }
  }

  @SpringBootApplication
  class AppConfig {
    @Bean
    def kubernetesClient(): KubernetesClient = {
      val client = new DefaultKubernetesClient()

      try {
        client.services().inNamespace("default").withName("kubernetes").get()
      } catch {
        case e: Exception =>
          throw new IllegalStateException("Unable to connect to kubernetes", e)
      }

      client
    }

    @Configuration
    @EnableConfigurationProperties(Array(classOf[EtcdProperties], classOf[DnsProperties], classOf[KubernetesProperties]): _*)
    class AkkaConfig {

      @Autowired
      var kubernetesClient: KubernetesClient = _

      @Autowired
      var etcdClient: EtcdClient = _

      @Autowired
      var etcdProperties: EtcdProperties = _

      @Autowired
      var dnsProperties: DnsProperties = _

      @Autowired
      var kubernetesProperties: KubernetesProperties = _

      @Bean
      def kubernetesRepository(): KubernetesRepository = {
        new KubernetesRepositoryImpl(kubernetesClient)
      }

      @Bean
      def skyDnsRepository(): SkyDnsRepository = {
        val masterUrl = if (kubernetesClient.getMasterUrl.toString.endsWith("/")) {
          kubernetesClient.getMasterUrl.toString.init
        } else {
          kubernetesClient.getMasterUrl.toString
        }
        val maxTimeout = FiniteDuration(etcdProperties.timeout, TimeUnit.SECONDS)
        new SkyDnsRepository(etcdClient, maxTimeout, "/skydns/", masterUrl)
      }

      @Bean
      def eventDispatcher(): ActorRef = {
        actorSystem().actorOf(EventDispatcher.props(kubernetesClient), "event-dispatcher")
      }

      @Bean
      def dnsRecordHandlerFactory(): (HasMetadata, ActorContext) => ActorRef = {
        (hasMetadata, context) => {
          val props = DnsRecordHandler.props(
            kubernetesClient, skyDnsRepository(), eventDispatcher(), hasMetadata, dnsProperties
          )
          context.actorOf(props)
        }
      }

      @Bean
      def dnsController(): ActorRef = {
        val decider = new DefaultDecider()

        val dnsControllerProps = DnsController.props(
          kubernetesRepository(),
          dnsRecordHandlerFactory(),
          decider,
          dnsProperties.controllerClass
        )

        val supervisorProps = BackoffSupervisor.props(
          Backoff.onFailure(
            dnsControllerProps,
            childName = "dns-controller",
            minBackoff = FiniteDuration(1, "second"),
            maxBackoff = FiniteDuration(1, "minutes"),
            randomFactor = 0.2
          ).withAutoReset(FiniteDuration(1, "minute"))
              .withSupervisorStrategy(
                OneForOneStrategy() {
                  case _ => SupervisorStrategy.Restart
                }))

        actorSystem().actorOf(supervisorProps)
      }

      private[AkkaConfig] trait SpringActorSystemAdapter {
        def actorSystem(): ActorSystem
        def shutdown(): Unit = {
          Await.result(actorSystem().terminate(), FiniteDuration(30, TimeUnit.SECONDS))
        }
      }

      @Bean(destroyMethod = "shutdown")
      def actorSystemAdapter(): SpringActorSystemAdapter = {
        new SpringActorSystemAdapter {
          private val akkaConfig = ConfigFactory.parseString(
            """
              |akka {
              |  loggers = ["akka.event.slf4j.Slf4jLogger"]
              |  loglevel = "DEBUG"
              |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
              |}
            """.stripMargin)

          private val system = ActorSystem.create("k8s-dns-sky", akkaConfig)
          override def actorSystem(): ActorSystem = system
        }
      }

      @Bean
      def actorSystem(): ActorSystem = actorSystemAdapter().actorSystem()

      @Bean
      def infoContributor(): InfoContributor = (builder: Info.Builder) => {
        import scala.collection.JavaConverters._
        val etcd = Map(
          "endpoints" -> etcdProperties.endpoints
        )
        builder.withDetail("etcd", etcd.asJava)

        val k8s = Map(
          "master" -> kubernetesClient.getMasterUrl
        )
        builder.withDetail("kubernetes", k8s.asJava)
      }
    }
  }

}
