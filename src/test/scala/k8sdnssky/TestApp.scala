package k8sdnssky
import java.util.Properties

import k8sdnssky.App.AppConfig
import org.springframework.boot.SpringApplication

object TestApp {

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
}
