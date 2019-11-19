package lila.ws

import akka.stream.scaladsl._
import io.lettuce.core._
import io.lettuce.core.pubsub._
import javax.inject._
import play.api.Logger
import scala.concurrent.{ Future, Promise, ExecutionContext, Await }
import scala.concurrent.duration._

import ipc._

@Singleton
final class Lila @Inject() (config: play.api.Configuration)(implicit ec: ExecutionContext) {

  import Lila._

  private val logger = Logger(getClass)
  private val redis = RedisClient create RedisURI.create(config.get[String]("redis.uri"))

  val connections: Connections = Await.result(establishConnections, 3.seconds)

  private def establishConnections: Future[Connections] =
    connect[LilaIn.Site, SiteOut](Chan("site")) { case out: SiteOut => out } zip
      connect[LilaIn.Tour, TourOut](Chan("tour")) { case out: TourOut => out } zip
      connect[LilaIn.Lobby, LobbyOut](Chan("lobby")) { case out: LobbyOut => out } zip
      connect[LilaIn.Simul, SimulOut](Chan("simul")) { case out: SimulOut => out } zip
      connect[LilaIn.Study, StudyOut](Chan("study")) { case out: StudyOut => out } zip
      connect[LilaIn.Round, RoundOut](Chan("round")) { case out: RoundOut => out } zip
      connect[LilaIn.Challenge, ChallengeOut](Chan("challenge")) { case out: ChallengeOut => out } map {
        case site ~ tour ~ lobby ~ simul ~ study ~ round ~ challenge => new Connections(
          site, tour, lobby, simul, study, round, challenge
        )
      }

  def emit: Emits = connections

  private def connect[In <: LilaIn, Out <: LilaOut](chan: Chan)(collect: PartialFunction[LilaOut, Out]): Future[Connection[In]] = {

    val connIn = redis.connectPubSub()
    val connOut = redis.connectPubSub()

    def emit(in: In): Unit = {
      val msg = in.write
      val timer = Monitor.redis.publishTime.start()
      connIn.async.publish(chan.in, msg).thenRun { timer.stop _ }
      Monitor.redis.in(chan.in, msg.takeWhile(' '.!=))
    }

    connOut.addListener(new RedisPubSubAdapter[String, String] {
      override def message(fromChan: String, msg: String): Unit = {
        Monitor.redis.out(fromChan, msg.takeWhile(' '.!=))
        LilaOut read msg match {
          case Some(out) => collect lift out match {
            case Some(typed) => LilaBus.publish(chan, typed)
            case None => logger.warn(s"Received $out on wrong channel: $fromChan")
          }
          case None => logger.warn(s"Unhandled $fromChan LilaOut: $msg")
        }
      }
    })

    val promise = Promise[Unit]
    connOut.async.subscribe(chan.out) thenRun { () =>
      connIn.async.publish(chan.in, LilaIn.WsBoot.write)
      promise.success(())
    }

    val close = () => {
      connIn.close()
      connOut.close()
    }

    promise.future map { _ =>
      new Connection[In](emit, close)
    }
  }

  def closeAll: Unit = {
    val c = connections
    List(c.site, c.tour, c.lobby, c.simul, c.study, c.round, c.challenge).foreach(_.close())
  }
}

object Lila {

  case class Chan(value: String) extends AnyVal with StringValue {
    def in = s"$value-in"
    def out = s"$value-out"
  }

  final class Connection[In <: LilaIn](val emit: In => Unit, val close: () => Unit) extends Emit[In] {
    def apply(in: In) = emit(in)
  }

  trait Emits {
    val site: Emit[LilaIn.Site]
    val tour: Emit[LilaIn.Tour]
    val lobby: Emit[LilaIn.Lobby]
    val simul: Emit[LilaIn.Simul]
    val study: Emit[LilaIn.Study]
    val round: Emit[LilaIn.Round]
    val challenge: Emit[LilaIn.Challenge]

    def apply[In](select: Emits => Emit[In], in: In) = select(this)(in)
  }

  final class Connections(
      val site: Connection[LilaIn.Site],
      val tour: Connection[LilaIn.Tour],
      val lobby: Connection[LilaIn.Lobby],
      val simul: Connection[LilaIn.Simul],
      val study: Connection[LilaIn.Study],
      val round: Connection[LilaIn.Round],
      val challenge: Connection[LilaIn.Challenge]
  ) extends Emits
}
