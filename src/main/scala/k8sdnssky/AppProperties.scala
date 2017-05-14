package k8sdnssky
import org.springframework.boot.context.properties.ConfigurationProperties

import scala.beans.BeanProperty

object AppProperties {

  @ConfigurationProperties(prefix = "dns")
  class DnsProperties {
    @BeanProperty var whitelist: String = _
    @BeanProperty var blacklist: String = _
    @BeanProperty var controllerClass: String = _

    def whitelistAsList: List[String] = {
      if (whitelist == null) {
        Nil
      } else {
        whitelist.split(",").map(_.trim).toList
      }
    }
    def blacklistAsList: List[String] = {
      if (blacklist == null) {
        Nil
      } else {
        blacklist.split(",").map(_.trim).toList
      }
    }
  }

  @ConfigurationProperties(prefix = "kubernetes")
  class KubernetesProperties {
    @BeanProperty var externalMasterUrl: String = _
  }

}
