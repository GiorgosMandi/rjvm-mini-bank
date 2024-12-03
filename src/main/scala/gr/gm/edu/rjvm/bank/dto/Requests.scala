package gr.gm.edu.rjvm.bank.dto

import akka.actor.typed.ActorRef
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Command.{CreateBankAccount, UpdateBalance}
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.{Command, Response}

object Requests {

  case class BankAccountCreationRequest(user: String, currency: String, balance: Double) {
    def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
  }

  case class FailureResponse(msg: String)

  case class BankAccountUpdateRequest(currency: String, amount: Double) {
    def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
  }
}
