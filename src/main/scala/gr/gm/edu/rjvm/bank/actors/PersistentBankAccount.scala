package gr.gm.edu.rjvm.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.util.{Failure, Try, Success}

// a single Bank account
object PersistentBankAccount {
  // commands = messages
  sealed trait Command

  object Command {
    case class CreateBankAccount(user: String,
                                 currency: String,
                                 initialBalance: Double,
                                 replyTo: ActorRef[Response]
                                ) extends Command

    case class UpdateBalance(id: String,
                             currency: String,
                             amount: Double,
                             replyTo: ActorRef[Response]
                            ) extends Command

    case class GetBankAccount(id: String,
                              replyTo: ActorRef[Response]
                             ) extends Command
  }

  import Command._

  // events = to persist Cassandra
  trait Event

  case class BankAccountCreated(bankAccount: BankAccount) extends Event

  case class BalanceUpdated(amount: Double) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  // response
  sealed trait Response

  object Response {
    case class BankAccountCreatedResponse(id: String) extends Response

    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Try[BankAccount]) extends Response

    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }
  import Response._

  /*
    - event sourcing: Journey to create data
      (+) fault-tolerant - in case the actor dies
      (+) auditing
      (-) More costly
   */
  // command handler =  message handler => persist an event
  private val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, bank) =>
        /*
         1. Banks create me
         2. bank sends me CreteBankAccount
         3. I persist BankAccountCreated
         4. I update my state
         5. reply back to bank with the BankAccountResponse
         6. (the bank surfaces the response to the HTTP server)
         */
        val id = state.id
        val bankAccount = BankAccount(id, user, currency, initialBalance)
        Effect
          .persist(BankAccountCreated(bankAccount)) // persisted into Cassandra
          .thenReply(bank)(_ => BankAccountCreatedResponse(id))

      case UpdateBalance(_, _, amount, replyTo) =>
        // todo check for withdraw
        val newBalance = state.balance + amount
        if (newBalance < 0)
          Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(Failure(new RuntimeException("Amount exceeds available budget."))))
        else
          Effect
            .persist(BalanceUpdated(amount))
            .thenReply(replyTo)(newState => BankAccountBalanceUpdatedResponse(Success(newState)))

      case GetBankAccount(_, replyTo) =>
        Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))
    }

  // event handler => update state
  private val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) =>
        bankAccount
      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), // will not be used
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
