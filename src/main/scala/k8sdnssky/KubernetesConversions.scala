package k8sdnssky
import io.fabric8.kubernetes.api.model.extensions.Ingress
import io.fabric8.kubernetes.api.model.{HasMetadata, LoadBalancerIngress, Service}

import scala.collection.mutable
import scala.collection.JavaConverters._

object KubernetesConversions {

  implicit class HasMetadataConvenience(val self: HasMetadata) extends AnyVal {
    def name: String = {
      if (self != null && self.getMetadata != null) {
        self.getMetadata.getName
      } else {
        null
      }
    }
    def namespace: String = {
      if (self != null && self.getMetadata != null) {
        self.getMetadata.getNamespace
      } else {
        null
      }
    }

    def annotation(name: String): String = {
      if (self != null && self.getMetadata != null && self.getMetadata.getAnnotations != null) {
        self.getMetadata.getAnnotations.get(name)
      } else {
        null
      }
    }

    def hasDnsControllerClass(name: String): Boolean = {
      val controllerClass = annotation(DnsController.ControllerAnnotation)
      if (name == null || name.isEmpty) {
        self match {
          case _: Ingress =>
            controllerClass == null || controllerClass.isEmpty
          case _ =>
            false
        }
      } else {
        controllerClass == name
      }
    }

    def hashKey: String = s"${self.getKind}/$name@$namespace"

    def asString: String = s"${self.getKind} ${self.name}@${self.namespace}"

    def hostnames: List[String] = {
      self match {
        case _: Service =>
          List(annotation(DnsController.DnsAnnotation))
        case ing: Ingress =>
          val spec = ing.getSpec
          if (spec == null) {
            Nil
          } else {
            val hostnames: mutable.Buffer[String] = mutable.Buffer()
            if (spec.getRules != null) {
              spec.getRules.asScala.foreach(rule => {
                if (rule.getHost != null) hostnames += rule.getHost
              })
            }
            if(spec.getTls!=null){
              spec.getTls.asScala.foreach(tlsRule => {
                if (tlsRule.getHosts != null) hostnames ++= tlsRule.getHosts.asScala
              })
            }
            hostnames.distinct.toList
          }
        case _ => Nil
      }
    }

    def loadBalancerIngress: Set[String] = {
      def toSet(lbIngress: java.util.List[LoadBalancerIngress]): Set[String] = {
        lbIngress.asScala.map(ing => {
          if (ing.getHostname != null) {
            ing.getHostname
          } else {
            ing.getIp
          }
        }
        ).filter(_ != null).toSet
      }

      self match {
        case svc: Service =>
          if (svc.getStatus != null && svc.getStatus.getLoadBalancer != null
              && svc.getStatus.getLoadBalancer.getIngress != null) {
            toSet(svc.getStatus.getLoadBalancer.getIngress)
          } else {
            Set.empty
          }
        case ing: Ingress =>
          if (ing.getStatus != null && ing.getStatus.getLoadBalancer != null
              && ing.getStatus.getLoadBalancer.getIngress != null) {
            toSet(ing.getStatus.getLoadBalancer.getIngress)
          } else {
            Set.empty
          }
        case _ => Set.empty
      }
    }
  }

}
