package gr.gm.edu.rjvm.bank.dto

import akka.actor.typed.ActorRef
import cats.implicits._
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Command.{CreateBankAccount, UpdateBalance}
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.{Command, Response}
import gr.gm.edu.rjvm.bank.http.Validation._


object Requests {

  case class FailureResponse(msg: String)

  case class BankAccountCreationRequest(user: String, currency: String, balance: Double) {
    def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
  }

  object BankAccountCreationRequest {
    implicit val validator: Validator[BankAccountCreationRequest] =
      (request: BankAccountCreationRequest) => {
        val userValidation = validateRequired(request.user, "user")
        val currencyValidation = validateRequired(request.currency, "currency")
        val balanceValidation = validateMinimum(request.balance, 0, "balance")

        // if all are valid, recreate request
        (userValidation, currencyValidation, balanceValidation)
          .mapN(BankAccountCreationRequest.apply)
      }
  }


  case class BankAccountUpdateRequest(currency: String, amount: Double) {
    def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
  }

  object BankAccountUpdateRequest {
    implicit val validator: Validator[BankAccountUpdateRequest] =
      (request: BankAccountUpdateRequest) => {
        val currencyValidation = validateRequired(request.currency, "currency")
        val amountAbsValidation = validateMinimumAbs(request.amount, 0.01, "balance")

        // if all are valid, recreate request
        (currencyValidation, amountAbsValidation)
          .mapN(BankAccountUpdateRequest.apply)
      }
  }
}
