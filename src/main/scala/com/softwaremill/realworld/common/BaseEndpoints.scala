package com.softwaremill.realworld.common

import com.softwaremill.realworld.articles.core.{ArticlesServerEndpoints, ArticlesService}
import com.softwaremill.realworld.auth.AuthService
import com.softwaremill.realworld.common.*
import com.softwaremill.realworld.common.BaseEndpoints.{UserWithEmailNotFoundMessage, defaultErrorOutputs}
import com.softwaremill.realworld.db.{Db, DbConfig}
import com.softwaremill.realworld.users.UsersRepository
import io.getquill.SnakeCase
import sttp.model.headers.WWWAuthenticateChallenge
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.server.ServerEndpoint.Full
import sttp.tapir.ztapir.*
import sttp.tapir.{Endpoint, EndpointIO, EndpointInput, EndpointOutput, PublicEndpoint, Validator}
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder}
import zio.{Cause, Exit, IO, Task, ZIO, ZLayer}

case class UserSession(userId: Int)

class BaseEndpoints(authService: AuthService, usersRepository: UsersRepository):

  val secureEndpoint: ZPartialServerEndpoint[Any, String, UserSession, Unit, ErrorInfo, Unit, Any] = endpoint
    .errorOut(defaultErrorOutputs)
    .securityIn(auth.http[String]("Token", WWWAuthenticateChallenge("Token")))
    .zServerSecurityLogic[Any, UserSession](handleAuth)

  val optionallySecureEndpoint: ZPartialServerEndpoint[Any, Option[String], Option[UserSession], Unit, ErrorInfo, Unit, Any] = endpoint
    .errorOut(defaultErrorOutputs)
    .securityIn(auth.http[Option[String]]("Token", WWWAuthenticateChallenge("Token")))
    .zServerSecurityLogic[Any, Option[UserSession]](handleAuthOpt)

  val publicEndpoint: PublicEndpoint[Unit, ErrorInfo, Unit, Any] = endpoint
    .errorOut(defaultErrorOutputs)

  private def handleAuth(token: String): IO[ErrorInfo, UserSession] = {
    (for {
      userEmail <- authService.verifyJwt(token)
      userId <- userIdByEmail(userEmail)
    } yield UserSession(userId)).logError.mapError {
      case e: Exceptions.Unauthorized => Unauthorized(e.message)
      case e: Exceptions.NotFound     => NotFound(e.message)
      case _                          => InternalServerError()
    }
  }

  private def handleAuthOpt(tokenOpt: Option[String]): IO[ErrorInfo, Option[UserSession]] = {
    tokenOpt match
      case Some(token) => handleAuth(token).map(userSession => Option(userSession))
      case None        => ZIO.none
  }

  private def userIdByEmail(email: String): Task[Int] =
    usersRepository.findUserIdByEmail(email).someOrFail(Exceptions.NotFound(UserWithEmailNotFoundMessage(email)))

object BaseEndpoints:
  private val UserWithEmailNotFoundMessage: String => String = (email: String) => s"User with email $email doesn't exist"

  val live: ZLayer[AuthService with UsersRepository, Nothing, BaseEndpoints] = ZLayer.fromFunction(new BaseEndpoints(_, _))

  val defaultErrorOutputs: EndpointOutput.OneOf[ErrorInfo, ErrorInfo] = oneOf[ErrorInfo](
    oneOfVariant(statusCode(StatusCode.BadRequest).and(jsonBody[BadRequest])),
    oneOfVariant(statusCode(StatusCode.Forbidden).and(jsonBody[Forbidden])),
    oneOfVariant(statusCode(StatusCode.NotFound).and(jsonBody[NotFound])),
    oneOfVariant(statusCode(StatusCode.Conflict).and(jsonBody[Conflict])),
    oneOfVariant(statusCode(StatusCode.Unauthorized).and(jsonBody[Unauthorized])),
    oneOfVariant(statusCode(StatusCode.UnprocessableEntity).and(jsonBody[ValidationFailed])),
    oneOfVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[InternalServerError]))
  )
