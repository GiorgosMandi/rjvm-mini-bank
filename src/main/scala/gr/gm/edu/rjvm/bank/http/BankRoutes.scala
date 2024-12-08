package gr.gm.edu.rjvm.bank.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import cats.data.Validated.{Invalid, Valid}
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Command._
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.Response._
import gr.gm.edu.rjvm.bank.actors.PersistentBankAccount.{Command, Response}
import gr.gm.edu.rjvm.bank.dto.Requests.{BankAccountCreationRequest, BankAccountUpdateRequest, FailureResponse}
import gr.gm.edu.rjvm.bank.http.Validation.{Validator, validateEntity}

import scala.concurrent.Future
import scala.util.{Failure, Success}
// this will make all case classes (de-)serializable
import io.circe.generic.auto._
// adds akka-http compatibility
import akka.actor.typed.scaladsl.AskPattern._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.duration._


class BankRoutes(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {

  implicit val timeout: Timeout = Timeout(5.seconds)

  private def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  private def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  private def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  private def validateRequest[R: Validator](request: R)(routeIfValid: Route): Route =
    validateEntity(request) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(failures) =>
        val err = failures.toList.map(_.errorMessage).mkString(",")
        complete(StatusCodes.BadRequest, FailureResponse(err))
    }

  /*
    POST /bank/
       Payload: bank account creation request as json
       Response
        201 Created
        Location: /bank/uuid

     GET /bank/uuid
        Response:
         200 OK
         JSON representation of bank account details

    PUT /bank/uuid
      Payload: (currency, amount) as JSON
      Response:
        1 - 200 OK
            Payload: new bank details as JSON
        2 - 404 Not Fount
        3 - 400 Bad request in case of wrong
   */
  val routes: Route =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        post {
          // parse the payload
          entity(as[BankAccountCreationRequest]) { request =>
            validateRequest(request) {
              /*
                 1. convert request into command for the bank actor
                 2. send the command to bank
                 3. expect reply
                 4. send back a response, based on the reply

               */
              onSuccess(createBankAccount(request)) {
                case BankAccountCreatedResponse(id) =>
                  respondWithHeader(Location(s"/bank/$id")) {
                    complete(StatusCodes.Created)
                  }
              }
            }
          }
        }
      } ~ // if not matched
        path(Segment) { id =>
          get {
            /*
                - send command to bank
                - expect reply
                - send back http response
             */
            onSuccess(getBankAccount(id)) {
              case GetBankAccountResponse(Some(account)) =>
                complete(StatusCodes.OK, account)
              case GetBankAccountResponse(None) =>
                complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found"))
            }
          } ~
            put {
              /*
                 - transform the request to a Command
                 - send the command to the bank
                 - expect a reply
                 - send back a http response
               */
              // todo validate request
              entity(as[BankAccountUpdateRequest]) { request =>
                validateRequest(request) {
                  onSuccess(updateBankAccount(id, request)) {
                    case BankAccountBalanceUpdatedResponse(Success(account)) =>
                      complete(StatusCodes.OK, account)
                    case BankAccountBalanceUpdatedResponse(Failure(exception)) =>
                      complete(StatusCodes.NotFound, FailureResponse(exception.getMessage))
                  }
                }
              }
            }
        }
    }
}
