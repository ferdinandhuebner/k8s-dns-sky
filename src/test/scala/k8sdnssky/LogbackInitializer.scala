package k8sdnssky
import ch.qos.logback.classic.{Level, LoggerContext}
import org.slf4j.LoggerFactory

trait LogbackInitializer {

  private val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  lc.getLogger("org.hibernate").setLevel(Level.WARN)
  lc.getLogger("org.jboss").setLevel(Level.WARN)

}
