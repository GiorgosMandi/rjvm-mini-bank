package gr.gm.edu.rjvm.bank.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.util.UUID
import scala.util.Failure

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
            case None => Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(Failure(new RuntimeException("Failed to find Bank account"))))
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
