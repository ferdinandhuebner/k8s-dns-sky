package k8sdnssky
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.{Actor, ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, MustMatchers}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.FiniteDuration

class SupervisionSuite extends TestKit(ActorSystem("dns-controller-suite"))
    with LogbackInitializer
    with ImplicitSender with FlatSpecLike with MustMatchers
    with MockitoSugar with BeforeAndAfterAll {

  private val log: Logger = LoggerFactory.getLogger(classOf[SupervisionSuite])

  class InitializingActor(
      name: String,
      initFn: () => Unit,
      receiveFn: (Any) => Unit,
      preRestartFn: () => Unit
  ) extends Actor {

    log.info(s"[name] initialize")
    initFn.apply()

    override def supervisorStrategy: SupervisorStrategy = {
      OneForOneStrategy() {
        case _ => SupervisorStrategy.Restart
      }
    }
    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      log.info(s"[$name] preRestart")
      preRestartFn.apply()
      super.preRestart(reason, message)
    }

    override def postStop(): Unit = {
      log.info(s"[name] postStop")
      super.postStop()
    }
    override def postRestart(reason: Throwable): Unit = {
      log.info(s"[name] postRestart")
      super.postRestart(reason)
    }
    override def receive: Receive = {
      case x: Any =>
        log.info(s"[name] receive")
        receiveFn(x)
    }
  }

  "a backoff supervisor" must "restart an actor that fails to initialize" in {
    val probe = TestProbe()
    object Failed
    object Succeeded

    val fail = new AtomicBoolean(true)
    val failOnceProps = Props(new InitializingActor(
      "failOnce",
      () => {
        if (fail.get()) {
          probe.ref.tell(Failed, ActorRef.noSender)
          fail.set(false)
          throw new RuntimeException("Failure requested")
        } else {
          probe.ref.tell(Succeeded, ActorRef.noSender)
        }
      }, (_) => {}, () => {}))

    val supervisorProps = BackoffSupervisor.props(
      Backoff.onFailure(
        failOnceProps,
        childName = "dns-controller",
        minBackoff = FiniteDuration(1, "second"),
        maxBackoff = FiniteDuration(5, "minutes"),
        randomFactor = 0.2
      ).withAutoReset(FiniteDuration(30, "seconds"))
          .withSupervisorStrategy(
            OneForOneStrategy() {
              case _ => SupervisorStrategy.Restart
            }))
    system.actorOf(supervisorProps)

    probe.expectMsg(Failed)
    probe.expectMsg(Succeeded)
    probe.expectNoMsg(FiniteDuration(1, "second"))
  }

  it must "restart an actor that fails during a receive operation" in {
    val probe = TestProbe()
    object Init
    object PreRestart
    object Fail
    object Succeed

    val failOnceProps = Props(new InitializingActor(
      "failOnMessage",
      () => {
        probe.ref.tell(Init, ActorRef.noSender)
      }, {
        case Fail => throw new RuntimeException("Failure requested")
        case x: Any => probe.ref.tell(x, ActorRef.noSender)
      },
      () => {
        probe.ref.tell(PreRestart, ActorRef.noSender)
      }
    ))

    val supervisorProps = BackoffSupervisor.props(
      Backoff.onFailure(
        failOnceProps,
        childName = "dns-controller",
        minBackoff = FiniteDuration(1, "second"),
        maxBackoff = FiniteDuration(1, "minute"),
        randomFactor = 0.2
      ).withAutoReset(FiniteDuration(1, "minutes"))
          .withSupervisorStrategy(
            OneForOneStrategy() {
              case _ => SupervisorStrategy.Restart
            }))
    val supervisor = system.actorOf(supervisorProps)

    probe.expectMsg(Init)
    supervisor ! Succeed
    probe.expectMsg(Succeed)
    supervisor ! Fail
    // with a backoff supervisor, prerestart is not called
    //probe.expectMsg(FiniteDuration(5, "minutes"), PreRestart)
    probe.expectMsg(Init)
    supervisor ! Succeed
    probe.expectMsg(Succeed)
    probe.expectNoMsg(FiniteDuration(1, "second"))
  }

  it must "restart an actor and its children if it fails during a receive operation" in {
    val probe = TestProbe()
    object Init
    object PreRestart
    object Fail
    object Succeed
    object SpawnChild

    class SpawningActor(name: String) extends Actor {
      log.info(s"[$name ${this.hashCode()}] initializing")
      probe.ref.tell(Init, ActorRef.noSender)
      val childCount = new AtomicInteger()

      override def preStart(): Unit = {
        log.info(s"[$name] preStart")
        super.preStart()
      }
      override def postStop(): Unit = {
        log.info(s"[$name] postStop")
        super.postStop()
      }
      override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
        log.info(s"[$name] preRestart")
        super.preRestart(reason, message)
      }
      override def postRestart(reason: Throwable): Unit = {
        log.info(s"[$name] postRestart")
        super.postRestart(reason)
      }

      override def receive: Receive = {
        case Fail =>
          log.info(s"[$name] failing")
          throw new RuntimeException("Failure requested")
        case SpawnChild =>
          log.info(s"[$name] spawning child")
          context.actorOf(Props(new SpawningActor("child-" + childCount.incrementAndGet())))
        case x: Any => probe.ref.tell(x, ActorRef.noSender)
      }
    }

    val props = Props(new SpawningActor("parent"))

    val supervisorProps = BackoffSupervisor.props(
      Backoff.onFailure(
        props,
        childName = "dns-controller",
        minBackoff = FiniteDuration(1, "second"),
        maxBackoff = FiniteDuration(1, "minute"),
        randomFactor = 0.2
      ).withAutoReset(FiniteDuration(1, "minute"))
          .withSupervisorStrategy(
            OneForOneStrategy() {
              case _ => SupervisorStrategy.Restart
            }))
    val supervisor = system.actorOf(supervisorProps)

    probe.expectMsg(Init)
    supervisor ! Succeed
    probe.expectMsg(Succeed)
    supervisor ! SpawnChild
    probe.expectMsg(Init)

    supervisor ! Fail
    // with a backoff supervisor, prerestart is not called
    //probe.expectMsg(FiniteDuration(5, "minutes"), PreRestart)
    probe.expectMsg(Init)
    supervisor ! Succeed
    probe.expectMsg(Succeed)
    probe.expectNoMsg(FiniteDuration(1, "second"))
  }
}