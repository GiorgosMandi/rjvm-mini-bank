package gr.gm.edu.rjvm.bank.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Command.{CreateBankAccount, GetBankAccount}
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Response
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Response.{BankAccountCreatedResponse, GetBankAccountResponse}

import java.util.UUID
import scala.concurrent.ExecutionContext

object Bank {

  // commands =  messages
  import PersistentBankAccount.Command
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._

  // events
  sealed trait Event

  case class BankAccountCreated(id: String) extends Event

  // states
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] =
    (state, command) =>
      command match {
        case createCommand@CreateBankAccount(_, _, _, _) =>
          // crete bank account and forward create command
          // note: state is not here updated
          val id = UUID.randomUUID().toString
          val newBankAccount = context.spawn(PersistentBankAccount(id), id)
          Effect
            .persist(BankAccountCreated(id))
            .thenReply(newBankAccount)(_ => createCommand)

        case updateCommand@UpdateBalance(id, _, _, replyTo) =>
          state.accounts.get(id) match {
            // forward message to bank account
            case Some(account) => Effect.reply(account)(updateCommand)
            case None => Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
          }
        case getCommand@GetBankAccount(id, replyTo) =>
          state.accounts.get(id) match {
            // forward message to bank account
            case Some(account) => Effect.reply(account)(getCommand)
            case None => Effect.reply(replyTo)(GetBankAccountResponse(None))
          }
      }

  // event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case BankAccountCreated(id) =>
        val account = context.child(id) // exists after command handler
          .getOrElse(context.spawn(PersistentBankAccount(id), id)) // does not exist in recovery mode
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account))
    }

  // behavior
  def apply(): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("bank"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object BankPlayground {
  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val bank = context.spawn(Bank(), "bank")

      val logger = context.log
      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case BankAccountCreatedResponse(id) =>
          logger.info(s"successfully created bank account $id")
          Behaviors.same
        case GetBankAccountResponse(maybeBankAccount) =>
          logger.info(s"Account details: $maybeBankAccount")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import akka.actor.typed.scaladsl.AskPattern._
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

//      bank ! CreateBankAccount("George", "EUR", 10, responseHandler)
      bank ! GetBankAccount("1f745290-b01a-11ef-af89-5de4ce72f4e4 ", responseHandler)
      "d1903538-19a9-472e-aa54-ca4ed015b29b"
//      bank.ask(replyTo => CreateBankAccount("George", "EUR", 10, replyTo)).flatMap {
//          case BankAccountCreatedResponse(id) =>
//            context.log.info("Successfully created bank account")
//            bank.ask(replyTo => GetBankAccount(id, replyTo))
//        }
//        .foreach {
//          case GetBankAccountResponse(maybeAccount) =>
//            context.log.info(s"Account details $maybeAccount")
//        }

      Behaviors.empty
    }

    val system = ActorSystem(rootBehavior, "BankDemo")
  }
}
