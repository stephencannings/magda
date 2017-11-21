package au.csiro.data61.magda.registry



import scala.concurrent.duration._

import org.flywaydb.core.Flyway
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers
import org.scalatest.fixture.FunSpec
import org.slf4j.LoggerFactory

import com.auth0.jwt.JWT

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ResponseEntity
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.AuthenticationFailedRejection
import akka.http.scaladsl.server.AuthorizationFailedRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestProbe
import au.csiro.data61.magda.Authentication
import au.csiro.data61.magda.client.AuthApiClient
import au.csiro.data61.magda.client.HttpFetcher
import au.csiro.data61.magda.model.Auth.AuthProtocols
import au.csiro.data61.magda.model.Auth.User
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import scalikejdbc._
import scala.collection.JavaConversions._

abstract class ApiSpec extends FunSpec with ScalatestRouteTest with Matchers with Protocols with SprayJsonSupport with MockFactory with AuthProtocols {
  case class FixtureParam(api: Api, webHookActorProbe: TestProbe, asAdmin: HttpRequest => HttpRequest, asNonAdmin: HttpRequest => HttpRequest, fetcher: HttpFetcher)

  val databaseUrl = Option(System.getenv("npm_package_config_databaseUrl")).getOrElse("jdbc:postgresql://localhost:5432/postgres")

  // Stop Flyway from producing so much spam that Travis terminates the process.
  LoggerFactory.getLogger("org.flywaydb").asInstanceOf[Logger].setLevel(Level.WARN)

  val flyway = new Flyway()
  flyway.setDataSource(databaseUrl, "postgres", "")
  flyway.setSchemas("test")
  flyway.setLocations("classpath:/sql")
  flyway.setPlaceholders(Map("clientUserName" -> "client", "clientPassword" -> "password"))

  override def testConfigSource =
    s"""
      |db.default.url = "${databaseUrl}?currentSchema=test"
      |authorization.skip = true
      |akka.loglevel = INFO
      |authApi.baseUrl = "http://localhost:6104"
      |authorization.skip=false
    """.stripMargin

  override def withFixture(test: OneArgTest) = {
    val webHookActorProbe = TestProbe()
    val httpFetcher = mock[HttpFetcher]

    val authClient = new AuthApiClient(httpFetcher)(testConfig, system, executor, materializer)
    val api = new Api(webHookActorProbe.ref, authClient, testConfig, system, executor, materializer)

    webHookActorProbe.expectMsg(1 millis, WebHookActor.Process)

    DB localTx { implicit session =>
      sql"DROP SCHEMA IF EXISTS test CASCADE".update.apply()
      sql"CREATE SCHEMA test".update.apply()
    }

    flyway.migrate()

    def asNonAdmin(req: HttpRequest): HttpRequest = {
      expectAdminCheck(httpFetcher, false)
      asUser(req)
    }

    def asAdmin(req: HttpRequest): HttpRequest = {
      expectAdminCheck(httpFetcher, true)
      asUser(req)
    }

    super.withFixture(test.toNoArgTest(FixtureParam(api, webHookActorProbe, asAdmin, asNonAdmin, httpFetcher)))
  }

  def asUser(req: HttpRequest): HttpRequest = {
    req.withHeaders(new RawHeader("X-Magda-Session", JWT.create().withClaim("userId", "1").sign(Authentication.algorithm)))
  }

  def expectAdminCheck(httpFetcher: HttpFetcher, isAdmin: Boolean) {
    val resFuture = Marshal(User(isAdmin)).to[ResponseEntity].map(user => HttpResponse(status = 200, entity = user))

    (httpFetcher.get _).expects("/v0/public/users/1", *).returns(resFuture)
  }

  def checkMustBeAdmin(fn: => HttpRequest) {
    describe("should only work when logged in as admin") {
      it("rejects with credentials missing if not signed in") { param =>
        fn ~> param.api.routes ~> check {
          expectCredentialsMissingRejection()
        }
      }

      it("rejects with credentials rejected if credentials are bad") { param =>
        fn.withHeaders(RawHeader("X-Magda-Session", "aergiajreog")) ~> param.api.routes ~> check {
          expectCredentialsRejectedRejection()
        }
      }

      it("rejects with AuthorizationFailedRejection if not admin") { param =>
        param.asNonAdmin(fn) ~> param.api.routes ~> check {
          expectUnauthorizedRejection()
        }
      }
    }

    def expectCredentialsMissingRejection() = {
      rejection match {
        case AuthenticationFailedRejection(AuthenticationFailedRejection.CredentialsMissing, _) => // success 
        case _ => fail()
      }
    }

    def expectCredentialsRejectedRejection() = {
      rejection match {
        case AuthenticationFailedRejection(AuthenticationFailedRejection.CredentialsRejected, _) => // success 
        case _ => fail(s"Rejection was $rejection")
      }
    }

    def expectUnauthorizedRejection() = {
      rejection shouldEqual AuthorizationFailedRejection
    }
  }

}
