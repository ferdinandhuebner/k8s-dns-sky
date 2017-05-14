package k8sdnssky
import java.io.{File, FileInputStream, InputStream}
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

import com.google.common.base.Stopwatch
import io.netty.handler.ssl.SslContextBuilder
import mousio.client.retry.RetryWithExponentialBackOff
import mousio.etcd4j.EtcdClient
import org.springframework.boot.actuate.health.{Health, HealthIndicator}
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.{Bean, Configuration}

import scala.util.Try

@Configuration
@EnableConfigurationProperties(Array(classOf[EtcdProperties]): _*)
class EtcdConfig(etcdProperties: EtcdProperties) {

  BouncyCastleUtil.ensureBouncyCastleProviderIsRegistered()

  private def tlsEnabled: Boolean = {
    if (etcdProperties.getCaFile != null || etcdProperties.getCertFile != null || etcdProperties.getKeyFile != null) {
      if (etcdProperties.getCaFile == null || etcdProperties.getCertFile == null || etcdProperties.getKeyFile == null) {
        throw new IllegalArgumentException("To enable TLS, etcd.ca-file, etcd.cert-file and etcd.key-file must be set.")
      } else {
        true
      }
    } else {
      false
    }
  }

  private def passwordAuthEnabled: Boolean = {
    if (etcdProperties.getUsername != null || etcdProperties.getPassword != null) {
      if (etcdProperties.getUsername == null || etcdProperties.getPassword == null) {
        throw new IllegalArgumentException("To enable username/password authentication, etcd.username and etcd.password must be set.")
      } else {
        true
      }
    } else {
      false
    }
  }

  private def endpoints: Array[URI] = {
    if (etcdProperties.getEndpoints == null) {
      throw new IllegalArgumentException("etcd.endpoints is not set")
    } else {
      etcdProperties.getEndpoints.split(",").map(URI.create)
    }
  }

  private def loadprivateKey(): InputStream = {
    BouncyCastleUtil.loadPrivateKeyAsPkcsEightString(() => {
      Files.newInputStream(new File(etcdProperties.getKeyFile).toPath)
    }).apply()
  }



  @Bean(destroyMethod = "close")
  def etcdClient(): EtcdClient = {
    val etcd = if (tlsEnabled) {
      val sslContext = SslContextBuilder.forClient()
          .trustManager(new File(etcdProperties.getCaFile))
          .keyManager(new FileInputStream(new File(etcdProperties.getCertFile)), loadprivateKey())
          .build()
      if (passwordAuthEnabled) {
        new EtcdClient(sslContext, etcdProperties.username, etcdProperties.password, endpoints: _*)
      } else {
        new EtcdClient(sslContext, endpoints: _*)
      }
    } else {
      if (passwordAuthEnabled) {
        new EtcdClient(etcdProperties.username, etcdProperties.password, endpoints: _*)
      } else {
        new EtcdClient(endpoints: _*)
      }
    }
    etcd.setRetryHandler(
      new RetryWithExponentialBackOff(
        etcdProperties.backoffMinDelay,
        etcdProperties.backoffMaxTries,
        etcdProperties.backoffMaxDelay)
    )

    val pingId = UUID.randomUUID().toString
    try {
      etcd.put(s"/ping-$pingId", pingId).ttl(etcdProperties.timeout * 2).timeout(etcdProperties.timeout, SECONDS).send().get()
      val response = etcd.get(s"/ping-$pingId").timeout(etcdProperties.timeout, SECONDS).send().get()
      if (response.getNode.getValue != pingId) {
        etcd.close()
        throw new IllegalStateException("Unable to verify connection to etcd")
      }
    } catch {
      case e: Throwable =>
        etcd.close()
        throw new IllegalStateException("Unable to verify connection to etcd", e)
    }

    etcd
  }
}
