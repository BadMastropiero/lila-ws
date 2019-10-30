package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, PostStop }
import play.api.libs.json.JsValue

import ipc._

object RoundClientActor {

  import ClientActor._

  case class State(
      room: RoomActor.State,
      site: ClientActor.State = ClientActor.State()
  )

  def start(roomState: RoomActor.State, fromVersion: Option[SocketVersion])(deps: Deps): Behavior[ClientMsg] = Behaviors.setup { ctx =>
    RoomActor.onStart(roomState, fromVersion, deps, ctx)
    apply(State(roomState), deps)
  }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] = Behaviors.receive[ClientMsg] { (ctx, msg) =>

    import deps._

    // def forward(payload: JsValue): Unit = queue(
    //   _.study,
    //   LilaIn.TellStudySri(state.room.id, LilaIn.TellSri(req.sri, req.user.map(_.id), payload))
    // )

    def receive: PartialFunction[ClientMsg, Behavior[ClientMsg]] = {

      case ClientCtrl.Broom(oldSeconds) =>
        if (state.site.lastPing < oldSeconds) Behaviors.stopped
        else {
          queue(_.round, LilaIn.KeepAlive(state.room.id))
          Behaviors.same
        }

      case ctrl: ClientCtrl => ClientActor.socketControl(state.site, deps.req.flag, ctrl)

      // case ClientOut.StudyForward(payload) =>
      //   forward(payload)
      //   Behaviors.same

      // default receive (site)
      case msg: ClientOutSite =>
        val siteState = globalReceive(state.site, deps, ctx, msg)
        if (siteState == state.site) Behaviors.same
        else apply(state.copy(site = siteState), deps)
    }

    RoomActor.receive(state.room, deps).lift(msg).fold(receive(msg)) {
      case (newState, emit) =>
        emit foreach queue.round.offer
        newState.fold(Behaviors.same[ClientMsg]) { roomState =>
          apply(state.copy(room = roomState), deps)
        }
    }

  }.receiveSignal {
    case (ctx, PostStop) =>
      onStop(state.site, deps, ctx)
      RoomActor.onStop(state.room, deps)
      Behaviors.same
  }
}
