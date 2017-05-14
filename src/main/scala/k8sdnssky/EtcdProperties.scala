package k8sdnssky
import org.springframework.boot.context.properties.ConfigurationProperties

import scala.beans.BeanProperty

@ConfigurationProperties(prefix = "etcd")
class EtcdProperties {

  @BeanProperty var endpoints: String = _
  @BeanProperty var username: String = _
  @BeanProperty var password: String = _
  @BeanProperty var certFile: String = _
  @BeanProperty var keyFile: String = _
  @BeanProperty var caFile: String = _
  @BeanProperty var timeout: Int = 5
  @BeanProperty var backoffMinDelay: Int = 20
  @BeanProperty var backoffMaxDelay: Int = 5000
  @BeanProperty var backoffMaxTries: Int = 3
}
