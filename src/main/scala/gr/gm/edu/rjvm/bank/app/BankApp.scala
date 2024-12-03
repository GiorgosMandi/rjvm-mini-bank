package gr.gm.edu.rjvm.bank.app

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import gr.gm.edu.rjvm.bank.actors.Bank
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Command
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.Http
import akka.util.Timeout
import gr.gm.edu.rjvm.bank.http.BankRoutes

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object BankApp {

  /*
    Starts HTTP Server and binds it to the routes
   */
  def startHttpServer(bank: ActorRef[Command]) (implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    val router = new BankRoutes(bank)
    val routes = router.routes

    val httpBindingFuture = Http().newServerAt("localhost", 8080).bind(routes)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"Server online at http://${address.getHostString}:${address.getPort}")
      case Failure(reason) =>
        system.log.error(s"Failed to bind HTTP server, because: $reason")
        system.terminate()
    }
  }


  def main(args: Array[String]): Unit = {

    trait RootCommand
    case class RetrieveBankActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

    val rootBehavior: Behavior[RootCommand] = Behaviors.setup{ context =>
      val bankActor = context.spawn(Bank(), "bank")

      Behaviors.receiveMessage {
        case RetrieveBankActor(replyTo) =>
          replyTo ! bankActor
          Behaviors.same
      }
    }
    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "BankSystem")
    implicit val ec: ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    val bankActorFuture: Future[ActorRef[Command]] = system.ask(replyTo =>  RetrieveBankActor(replyTo))
    bankActorFuture.foreach(startHttpServer)
  }
}
