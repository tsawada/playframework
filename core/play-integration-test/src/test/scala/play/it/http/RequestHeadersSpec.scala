/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.it.http

import org.specs2.matcher.MatchResult
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test._
import play.api.Configuration
import play.api.Mode
import play.core.server.ServerConfig
import play.it._

class NettyRequestHeadersSpec extends RequestHeadersSpec with NettyIntegrationSpecification {
  // Netty passes an HTTP/1.0 request without Host through to Play, which retains missing-Host compatibility.
  protected override val backendPassesHttp10WithoutHostToPlay = true
  // Netty passes an empty Host on an absolute-form request through to Play, which applies target precedence.
  protected override val backendPassesAbsoluteTargetWithEmptyHostToPlay = true
}

class PekkoHttpRequestHeadersSpec extends RequestHeadersSpec with PekkoHttpIntegrationSpecification {
  // Pekko HTTP rejects a relative request without Host while establishing its effective URI, before Play sees it.
  protected override val backendPassesHttp10WithoutHostToPlay = false
  // Pekko HTTP currently rejects an empty Host against an absolute target before Play's request conversion runs.
  protected override val backendPassesAbsoluteTargetWithEmptyHostToPlay = false

  "Pekko HTTP request header handling" should {
    "not complain about invalid User-Agent headers" in {
      // This test modifies the global (!) logger to capture log messages.
      // The test will not be reliable when run concurrently. However, since
      // we're checking for the *absence* of log messages the worst thing
      // that will happen is that the test will pass when it should fail. We
      // should not get spurious failures which would cause our CI testing
      // to fail. I think it's still worth including this test because it
      // will still often report correct failures, even if it's not perfect.

      withServerAndConfig()((Action, _) => Action { rh => Results.Ok(rh.headers.get("User-Agent").toString) }) { port =>
        def testAgent(agent: String) = {
          val (_, logMessages) = LogTester.recordLogEvents {
            val Seq(response) = BasicHttpClient.makeRequests(port)(
              BasicRequest(
                "GET",
                "/",
                "HTTP/1.1",
                Map(
                  "User-Agent" -> agent
                ),
                ""
              )
            )
            response.body must beLeft(s"Some($agent)")
          }
          logMessages.map(_.getFormattedMessage) must not contain (contain(agent))
        }
        // These agent strings come from https://github.com/playframework/playframework/issues/7997
        testAgent(
          "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0_3 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Mobile/15A432 [FBAN/FBIOS;FBAV/147.0.0.46.81;FBBV/76961488;FBDV/iPhone8,1;FBMD/iPhone;FBSN/iOS;FBSV/11.0.3;FBSS/2;FBCR/T-Mobile.pl;FBID/phone;FBLC/pl_PL;FBOP/5;FBRV/0]"
        )
        testAgent(
          "Mozilla/5.0 (Linux; Android 7.0; TRT-LX1 Build/HUAWEITRT-LX1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/61.0.3163.98 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/148.0.0.51.62;]"
        )
        testAgent(
          "Mozilla/5.0 (Linux; Android 7.0; SM-G955F Build/NRD90M; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/62.0.3202.84 Mobile Safari/537.36 [FB_IAB/Orca-Android;FBAV/142.0.0.18.63;]"
        )
      }
    }
  }
}

trait RequestHeadersSpec extends PlaySpecification with ServerIntegrationSpecification with HttpHeadersCommonSpec {

  /** Pekko rejects a missing Host while establishing its effective URI, including for HTTP/1.0. */
  protected def backendPassesHttp10WithoutHostToPlay: Boolean

  /** Whether the backend passes an absolute-form request with an empty Host field to Play. */
  protected def backendPassesAbsoluteTargetWithEmptyHostToPlay: Boolean

  def withServerAndConfig[T](
      configuration: (String, Any)*
  )(action: (DefaultActionBuilder, PlayBodyParsers) => EssentialAction)(block: Port => T): T = {
    val serverConfig: ServerConfig = {
      val c = ServerConfig(port = Some(testServerPort), mode = Mode.Test)
      c.copy(configuration = Configuration(configuration*).withFallback(c.configuration))
    }
    runningWithPort(
      play.api.test.TestServer(
        serverConfig,
        GuiceApplicationBuilder()
          .appRoutes { app =>
            val Action = app.injector.instanceOf[DefaultActionBuilder]
            val parse  = app.injector.instanceOf[PlayBodyParsers]
            ({
              case _ => action(Action, parse)
            })
          }
          .build(),
        Some(integrationServerProvider)
      )
    ) { port =>
      block(port)
    }
  }

  def withServer[T](action: (DefaultActionBuilder, PlayBodyParsers) => EssentialAction)(block: Port => T): T = {
    withServerAndConfig()(action)(block)
  }

  "Play request header handling" should {
    "get request headers properly" in withServer((Action, _) =>
      Action { rh => Results.Ok(rh.headers.getAll("Origin").mkString(",")) }
    ) { port =>
      val Seq(response) = BasicHttpClient.makeRequests(port)(
        BasicRequest("GET", "/", "HTTP/1.1", Map("origin" -> "http://foo"), "")
      )
      response.body.left.toOption must beSome("http://foo")
    }

    "remove request headers properly" in withServer((Action, _) =>
      Action { rh => Results.Ok(rh.headers.remove("ORIGIN").getAll("Origin").mkString(",")) }
    ) { port =>
      val Seq(response) = BasicHttpClient.makeRequests(port)(
        BasicRequest("GET", "/", "HTTP/1.1", Map("origin" -> "http://foo"), "")
      )
      response.body.left.toOption must beSome("")
    }

    "replace request headers properly" in withServer((Action, _) =>
      Action { rh => Results.Ok(rh.headers.replace("Origin" -> "https://bar.com").getAll("Origin").mkString(",")) }
    ) { port =>
      val Seq(response) = BasicHttpClient.makeRequests(port)(
        BasicRequest("GET", "/", "HTTP/1.1", Map("origin" -> "http://foo"), "")
      )
      response.body.left.toOption must beSome("https://bar.com")
    }

    "not expose a content-type when there's no body" in withServer((Action, _) =>
      Action { rh =>
        // the body is a String representation of `get("Content-Type")`
        Results.Ok(rh.headers.get("Content-Type").getOrElse("no-header"))
      }
    ) { port =>
      val Seq(response) = BasicHttpClient.makeRequests(port)(
        // an empty body implies no parsing is used and no content type is derived from the body.
        BasicRequest("GET", "/", "HTTP/1.1", Map.empty, "")
      )
      response.body.left.toOption must beSome("no-header")
    }

    "pass common tests for headers" in withServer((Action, _) =>
      Action { rh =>
        commonTests(rh.headers)
        Results.Ok("Done")
      }
    ) { port =>
      val Seq(response) = BasicHttpClient.makeRequests(port)(
        BasicRequest(
          "GET",
          "/",
          "HTTP/1.1",
          Map("a" -> "a2", "a" -> "a1", "b" -> "b3", "b" -> "b2", "B" -> "b1", "c" -> "c1"),
          ""
        )
      )
      response.status must_== 200
    }

    "get request headers properly when Content-Encoding is set" in {
      withServer((Action, _) =>
        Action { rh =>
          Results.Ok(
            Seq("Content-Encoding", "Authorization", "X-Custom-Header")
              .map { headerName => s"$headerName -> ${rh.headers.get(headerName)}" }
              .mkString(", ")
          )
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map(
              "Content-Encoding" -> "gzip",
              "Authorization"    -> "Bearer 123",
              "X-Custom-Header"  -> "123"
            ),
            ""
          )
        )
        response.body must beLeft(
          "Content-Encoding -> None, " +
            "Authorization -> Some(Bearer 123), " +
            "X-Custom-Header -> Some(123)"
        )
      }
    }

    "preserve the value of headers" in {
      def headerValueInRequest(headerName: String, headerValue: String): MatchResult[Either[String, ?]] = {
        withServer((Action, _) => Action { rh => Results.Ok(rh.headers.get(headerName).toString) }) { port =>
          val Seq(response) = BasicHttpClient.makeRequests(port)(
            // an empty body implies no parsing is used and no content type is derived from the body.
            BasicRequest("GET", "/", "HTTP/1.1", Map(headerName -> headerValue), "")
          )
          response.body must beLeft(s"Some($headerValue)")
        }
      }
      // This example comes from https://github.com/playframework/playframework/issues/7719
      "for UTF-8 Content-Disposition headers" in headerValueInRequest(
        "Content-Disposition",
        "attachment; filename*=UTF-8''Roget%27s%20Thesaurus.pdf"
      )
      // This example comes from https://github.com/playframework/playframework/issues/7737#issuecomment-323335828
      "for Authorization headers" in headerValueInRequest(
        "Authorization",
        """OAuth realm="https://api.clever-cloud.com/v2/oauth", oauth_consumer_key="<key>", oauth_token="<token>", oauth_signature_method="HMAC-SHA512", oauth_signature="<signature>", oauth_timestamp="1502979668", oauth_nonce="402047""""
      )
    }

    "preserve the case of header names" in {
      def headerNameInRequest(headerName: String, headerValue: String): MatchResult[Either[String, ?]] = {
        withServer((Action, _) =>
          Action { rh => Results.Ok(rh.headers.keys.filter(_.equalsIgnoreCase(headerName)).mkString) }
        ) { port =>
          val Seq(response) = BasicHttpClient.makeRequests(port)(
            // an empty body implies no parsing is used and no content type is derived from the body.
            BasicRequest("GET", "/", "HTTP/1.1", Map(headerName -> headerValue), "")
          )
          response.body must beLeft(headerName)
        }
      }
      "'Foo' header" in headerNameInRequest("Foo", "Bar")
      "'foo' header" in headerNameInRequest("foo", "bar")
      // Authorization examples taken from https://github.com/playframework/playframework/issues/7735
      "'Authorization' header" in headerNameInRequest("Authorization", "some value")
      "'authorization' header" in headerNameInRequest("authorization", "some value")
      // User agent examples taken from https://github.com/playframework/playframework/issues/7735#issuecomment-360180932
      "'User-Agent' header with valid value" in headerNameInRequest(
        "User-Agent",
        """Mozilla/5.0 (iPhone; CPU iPhone OS 11_2_2 like Mac OS X) AppleWebKit/604.4.7 (KHTML, like Gecko) Mobile/15C202"""
      )
      "'User-Agent' header with invalid value" in headerNameInRequest(
        "User-Agent",
        """Mozilla/5.0 (iPhone; CPU iPhone OS 11_2_2 like Mac OS X) AppleWebKit/604.4.7 (KHTML, like Gecko) Mobile/15C202 [FBAN/FBIOS;FBAV/155.0.0.36.93;FBBV/87992437;FBDV/iPhone9,3;FBMD/iPhone;FBSN/iOS;FBSV/11.2.2;FBSS/2;FBCR/3Ireland;FBID/phone;FBLC/en_US;FBOP/5;FBRV/0]"""
      )
    }

    "reject an absolute https target received over untrusted plaintext transport" in {
      withServer((Action, _) => Action(Results.Ok)) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest("GET", "https://localhost/path", "HTTP/1.1", Map.empty, "")
        )

        response.status must_== BAD_REQUEST
      }
    }

    "use an absolute scheme that matches the direct transport" in {
      withServer((Action, _) =>
        Action { request => Results.Ok(s"${request.scheme.render}|${request.host}|${request.uri}") }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest("GET", "http://localhost/path", "HTTP/1.1", Map.empty, "")
        )

        response.body must beLeft("http|localhost|http://localhost/path")
      }
    }

    "normalize an empty absolute-form path while retaining the raw request target and query" in {
      withServer((Action, _) =>
        Action { request =>
          Results.Ok(
            Seq(
              request.path,
              request.asJava.path,
              request.uri,
              request.target.queryString,
              request.getQueryString("key")
            ).mkString("|")
          )
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest("GET", "http://localhost?key=value", "HTTP/1.1", Map.empty, "")
        )

        response.body must beLeft("/|/|http://localhost?key=value|key=value|Some(value)")
      }
    }

    "apply an absolute target authority when an empty HTTP/1.1 Host field reaches Play" in {
      withServer((Action, _) =>
        Action { request => Results.Ok(s"${request.host}|${request.headers.getAll(HOST).mkString}") }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "http://LOCALHOST/path",
            "HTTP/1.1",
            Map(HOST -> ""),
            "",
            includeHost = false
          )
        )

        if (backendPassesAbsoluteTargetWithEmptyHostToPlay) {
          response.status must_== OK
          response.body must beLeft("localhost|localhost")
        } else {
          // Pekko HTTP's effective-URI stage currently enforces Host/target equality before Play can apply RFC 9112
          // target precedence. Keep this raw test so that an upstream fix immediately exercises Play's acceptance.
          response.status must_== BAD_REQUEST
        }
      }
    }

    "accept an absolute public scheme supplied through a trusted gateway" in {
      withServerAndConfig("play.http.forwarded.version" -> "rfc7239")((Action, _) =>
        Action { request => Results.Ok(s"${request.scheme.render}|${request.secure}|${request.uri}") }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "https://localhost/path",
            "HTTP/1.1",
            Map("Forwarded" -> "for=203.0.113.43;proto=https"),
            ""
          )
        )

        response.body must beLeft("https|true|https://localhost/path")
      }
    }

    "accept an absolute backend scheme supplied through a trusted gateway" in {
      withServerAndConfig("play.http.forwarded.version" -> "rfc7239")((Action, _) =>
        Action { request => Results.Ok(s"${request.scheme.render}|${request.secure}|${request.uri}") }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "http://localhost/path",
            "HTTP/1.1",
            Map("Forwarded" -> "for=203.0.113.43;proto=https"),
            ""
          )
        )

        response.body must beLeft("https|true|http://localhost/path")
      }
    }

    "reject a missing Host field in HTTP/1.1 origin-form and absolute-form requests" in {
      withServer((Action, _) => Action(Results.Ok)) { port =>
        Seq("/", "http://localhost/").foreach { target =>
          val Seq(response) = BasicHttpClient.makeRequests(port)(
            BasicRequest("GET", target, "HTTP/1.1", Map.empty, "", includeHost = false)
          )
          response.status must_== BAD_REQUEST
        }
        ok
      }
    }

    "reject an invalid single Host field before absolute-form or CONNECT precedence" in {
      withServer((Action, _) => Action(Results.Ok)) { port =>
        Seq("GET" -> "http://localhost/", "CONNECT" -> "localhost:443").foreach {
          case (method, target) =>
            val Seq(response) = BasicHttpClient.makeRequests(port)(
              BasicRequest(
                method,
                target,
                "HTTP/1.1",
                Map(HOST -> "user@ignored.example"),
                "",
                includeHost = false
              )
            )
            response.status must_== BAD_REQUEST
        }
        ok
      }
    }

    "retain HTTP/1.0 compatibility when Host is absent" in {
      withServerAndConfig("play.filters.hosts.allowed" -> Seq(""))((Action, _) =>
        Action { request =>
          Results.Ok(s"${request.version}|${request.authority.isEmpty}|${request.headers.get(HOST)}")
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest("GET", "/", "HTTP/1.0", Map.empty, "", includeHost = false)
        )

        if (backendPassesHttp10WithoutHostToPlay) {
          response.body must beLeft("HTTP/1.0|true|None")
        } else {
          response.status must_== BAD_REQUEST
        }
      }
    }

    "reject invalid CONNECT request targets" in {
      withServer((Action, _) => Action(Results.Ok)) { port =>
        Seq("http://localhost:443", "localhost:0", "localhost:65536").foreach { target =>
          val Seq(response) = BasicHttpClient.makeRequests(port)(
            BasicRequest("CONNECT", target, "HTTP/1.1", Map.empty, "")
          )
          response.status must_== BAD_REQUEST
        }
        ok
      }
    }

    "return a stable bad request for userinfo in an absolute authority" in {
      withServer((Action, _) => Action(Results.Ok)) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port, checkClosed = true)(
          BasicRequest("GET", "http://user@localhost/path", "HTTP/1.1", Map.empty, "")
        )

        // HTTP request authorities model host[:port], not userinfo. Strict request conversion rejects
        // user@localhost, and both server backends must report that validation failure as a stable 400.
        response.status must_== BAD_REQUEST
      }
    }

    "retain direct transport metadata when selecting a forwarded remote node" in {
      withServerAndConfig("play.http.forwarded.version" -> "rfc7239")((Action, _) =>
        Action { request =>
          Results.Ok(
            Seq(
              request.transport.peer.address.isLoopbackAddress,
              request.transport.peer.port.exists(_ > 0),
              request.transport.tls.isDefined,
              request.remote.identity,
              request.remote.port,
              request.secure
            ).mkString("|")
          )
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map("Forwarded" -> "for=203.0.113.43;proto=https"),
            ""
          )
        )

        response.body must beLeft("true|true|false|203.0.113.43|None|true")
      }
    }

    "keep a non-IP selected remote separate from the direct transport peer" in {
      withServerAndConfig("play.http.forwarded.version" -> "rfc7239")((Action, _) =>
        Action { request =>
          Results.Ok(
            Seq(
              request.transport.peer.address.isLoopbackAddress,
              request.transport.tls.isEmpty,
              request.remote.identity,
              request.remote.ipAddress.isEmpty,
              request.scheme.render
            ).mkString("|")
          )
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map("Forwarded" -> "for=_hidden;proto=https"),
            ""
          )
        )

        response.body must beLeft("true|true|_hidden|true|https")
      }
    }

    "use trusted x-forwarded-ssl throughout the request" in {
      withServerAndConfig(
        "play.http.forwarded.version"            -> "x-forwarded",
        "play.http.forwarded.trustXForwardedSsl" -> true
      )((Action, _) =>
        Action { request =>
          Results.Ok(s"${request.secure}|${Call("GET", "/result").absoluteURL()(using request)}")
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/path",
            "HTTP/1.1",
            Map("X-Forwarded-Ssl" -> "on"),
            ""
          )
        )

        response.body must beLeft("true|https://localhost/result")
      }
    }

    "use trusted rfc7239 proto without a forwarded identity throughout the request" in {
      withServerAndConfig("play.http.forwarded.version" -> "rfc7239")((Action, _) =>
        Action { request =>
          Results.Ok(s"${request.secure}|${Call("GET", "/result").absoluteURL()(using request)}")
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/path",
            "HTTP/1.1",
            Map("Forwarded" -> "proto=https"),
            ""
          )
        )

        response.body must beLeft("true|https://localhost/result")
      }
    }

    "keep the original host when forwarded host handling is disabled" in {
      withServerAndConfig("play.http.forwarded.version" -> "rfc7239")((Action, _) =>
        Action { rh => Results.Ok(s"${rh.host}|${rh.headers(HOST)}") }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map("Forwarded" -> "for=192.0.2.43;host=public.example"),
            ""
          )
        )
        response.body must beLeft("localhost|localhost")
      }
    }

    "expose one typed forwarded scheme and authority consistently" in {
      withServerAndConfig(
        "play.http.forwarded.version"            -> "rfc7239",
        "play.http.forwarded.trustForwardedHost" -> true
      )((Action, _) =>
        Action { request =>
          Results.Ok(
            Seq(
              request.scheme.render,
              request.asJava.scheme.render,
              request.authority.map(_.render).getOrElse("<none>"),
              request.asJava.authority.orElseThrow().render,
              request.host,
              request.headers(HOST),
              request.secure
            ).mkString("|")
          )
        }
      ) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map("Forwarded" -> "for=203.0.113.43;proto=HtTpS;host=\"PUBLIC.EXAMPLE:08443\""),
            ""
          )
        )

        response.body must beLeft(
          "https|https|public.example:8443|public.example:8443|public.example:8443|public.example:8443|true"
        )
      }
    }

    "keep the original host when a forwarded host is invalid" in {
      withServerAndConfig(
        "play.http.forwarded.version"            -> "rfc7239",
        "play.http.forwarded.trustForwardedHost" -> true
      )((Action, _) => Action { rh => Results.Ok(s"${rh.host}|${rh.headers(HOST)}") }) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map("Forwarded" -> "for=192.0.2.43;host=\"user@example.org\""),
            ""
          )
        )
        response.body must beLeft("localhost|localhost")
      }
    }

    "use a trusted forwarded host with an absolute request target" in {
      withServerAndConfig(
        "play.http.forwarded.version"            -> "rfc7239",
        "play.http.forwarded.trustForwardedHost" -> true
      )((Action, _) => Action { rh => Results.Ok(s"${rh.host}|${rh.headers(HOST)}") }) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "http://localhost/path",
            "HTTP/1.1",
            Map("Forwarded" -> "for=192.0.2.43;host=public.example"),
            ""
          )
        )
        response.body must beLeft("public.example|public.example")
      }
    }

    "validate the trusted forwarded host with the allowed hosts filter" in {
      withServerAndConfig(
        "play.http.forwarded.version"            -> "rfc7239",
        "play.http.forwarded.trustForwardedHost" -> true,
        "play.filters.enabled"                   -> Seq(classOf[AllowedHostsFilter].getName),
        "play.filters.hosts.allowed"             -> Seq("public.example")
      )((Action, _) => Action { rh => Results.Ok(rh.host) }) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map("Forwarded" -> "for=192.0.2.43;host=public.example"),
            ""
          )
        )
        response.status must_== OK
        response.body must beLeft("public.example")
      }
    }

    "reject a trusted forwarded host not accepted by the allowed hosts filter" in {
      withServerAndConfig(
        "play.http.forwarded.version"            -> "rfc7239",
        "play.http.forwarded.trustForwardedHost" -> true,
        "play.filters.enabled"                   -> Seq(classOf[AllowedHostsFilter].getName),
        "play.filters.hosts.allowed"             -> Seq("localhost")
      )((Action, _) => Action { rh => Results.Ok(rh.host) }) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/",
            "HTTP/1.1",
            Map("Forwarded" -> "for=192.0.2.43;host=public.example"),
            ""
          )
        )
        response.status must_== BAD_REQUEST
      }
    }

    "validate the trusted x-forwarded-host with the allowed hosts filter" in {
      withServerAndConfig(
        "play.http.forwarded.version"             -> "x-forwarded",
        "play.http.forwarded.trustXForwardedHost" -> true,
        "play.filters.enabled"                    -> Seq(classOf[AllowedHostsFilter].getName),
        "play.filters.hosts.allowed"              -> Seq("public.example")
      )((Action, _) => Action { request => Results.Ok(request.host) }) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/path",
            "HTTP/1.1",
            Map("X-Forwarded-Host" -> "public.example"),
            ""
          )
        )

        response.status must_== OK
        response.body must beLeft("public.example")
      }
    }

    "reject an x-forwarded-host not accepted by the allowed hosts filter" in {
      withServerAndConfig(
        "play.http.forwarded.version"             -> "x-forwarded",
        "play.http.forwarded.trustXForwardedHost" -> true,
        "play.filters.enabled"                    -> Seq(classOf[AllowedHostsFilter].getName),
        "play.filters.hosts.allowed"              -> Seq("internal.example")
      )((Action, _) => Action { request => Results.Ok(request.host) }) { port =>
        val Seq(response) = BasicHttpClient.makeRequests(port)(
          BasicRequest(
            "GET",
            "/path",
            "HTTP/1.1",
            Map("X-Forwarded-Host" -> "public.example"),
            ""
          )
        )

        response.status must_== BAD_REQUEST
      }
    }

    "respect max header value setting" in {
      withServerAndConfig("play.server.max-header-size" -> "64")((Action, _) => Action(Results.Ok)) { port =>
        val responses = BasicHttpClient.makeRequests(port)(
          // Only has valid headers that don't exceed 64 chars
          BasicRequest("GET", "/", "HTTP/1.1", Map("h" -> "valid"), ""),
          // Has a header that exceeds 64 bytes
          BasicRequest("GET", "/", "HTTP/1.1", Map("h" -> "invalid" * 64), "")
        )

        responses.head.status must beEqualTo(OK)
        responses.last.status must beOneOf(
          // Pekko-HTTP returns a "431 Request Header Fields Too Large" when the header value exceeds
          // the max value length configured. And Netty returns a 414 URI Too Long.
          REQUEST_HEADER_FIELDS_TOO_LARGE,
          REQUEST_URI_TOO_LONG
        )
      }
    }

    "maintain uri and path consistency" in {
      def uriInRequest(uri: String): MatchResult[Either[String, ?]] = {
        withServer((Action, _) =>
          Action { rh => Results.Ok((rh.uri.contains(rh.path) && rh.uri.contains(rh.rawQueryString)).toString) }
        ) { port =>
          val Seq(response) = BasicHttpClient.makeRequests(port)(
            BasicRequest("GET", uri, "HTTP/1.1", Map(), "")
          )
          response.body must beLeft(s"true")
        }
      }
      "encoded uri" in uriInRequest("/foo%3Abar?bar%3Abaz=foo")
      "decoded uri" in uriInRequest("/foo:bar?bar:baz=foo")
    }
  }
}
