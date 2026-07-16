/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.common

import org.specs2.mutable.Specification
import play.api.mvc.request.Scheme
import play.core.server.common.ForwardedHeaderHandler._

class ForwardedHeaderAuthoritySpec extends Specification with ForwardedHeaderHandlerSpecSupport {
  "ForwardedHeaderHandler" should {
    "use rfc7239 host from the selected trusted forwarded entry" in {
      val forwarding = forwardedRequestToLocalhost(
        version("rfc7239") ++
          trustedProxies("127.0.0.1", "192.168.1.1/24") ++
          trustForwardedHost(true),
        """
          |Host: internal.example
          |Forwarded: for=203.0.113.43;host="public.example:9443"
          |Forwarded: for=192.168.1.43;host=proxy.example
        """.stripMargin
      )

      forwarding.authority.map(_.render) must beSome("public.example:9443")
      forwarding.remote.identity mustEqual "203.0.113.43"
    }

    "not use rfc7239 host by default" in {
      forwardedHostToLocalhost(
        version("rfc7239") ++ trustedProxies("127.0.0.1"),
        """
          |Forwarded: for=203.0.113.43;host=public.example
        """.stripMargin
      ) must beNone
    }

    "ignore rfc7239 host when the forwarding proxy is not trusted" in {
      forwardedHostToLocalhost(
        version("rfc7239") ++ trustedProxies("192.168.1.1/24") ++ trustForwardedHost(true),
        """
          |Host: internal.example
          |Forwarded: for=203.0.113.43;host=public.example
        """.stripMargin
      ) must beNone
    }

    "preserve the last verified rfc7239 authority when an earlier entry omits host" in {
      forwardedHostToLocalhost(
        version("rfc7239") ++
          trustedProxies("127.0.0.1", "192.168.1.1/24") ++
          trustForwardedHost(true),
        """
          |Host: internal.example
          |Forwarded: for=203.0.113.43
          |Forwarded: for=192.168.1.43;host=proxy.example
        """.stripMargin
      ) must beSome("proxy.example")
    }

    "ignore empty-host rfc7239 authorities without discarding other metadata" in {
      val forwarding = Seq("", ":8080").map { host =>
        forwardedRequestToLocalhost(
          version("rfc7239") ++ trustedProxies("127.0.0.1") ++ trustForwardedHost(true),
          s"""Forwarded: for=203.0.113.43;proto=https;host="$host"""",
          "internal.example:9000"
        )
      }

      forwarding.map(_.authority.map(_.render)) mustEqual Seq.fill(2)(Some("internal.example:9000"))
      forwarding.map(_.remote.identity) mustEqual Seq.fill(2)("203.0.113.43")
      forwarding.map(_.scheme) mustEqual Seq.fill(2)(Scheme.Https)
    }

    "not reject an empty-host rfc7239 authority when host forwarding is disabled" in {
      val forwarding = forwardedRequestToLocalhost(
        version("rfc7239") ++ trustedProxies("127.0.0.1"),
        """Forwarded: for=203.0.113.43;proto=https;host=":8080"""",
        "internal.example:9000"
      )

      forwarding.authority.map(_.render) must beSome("internal.example:9000")
      forwarding.remote.identity mustEqual "203.0.113.43"
      forwarding.scheme mustEqual Scheme.Https
    }

    "use rfc7239 host without for from a directly trusted proxy" in {
      val forwarding = forwardedRequestToLocalhost(
        version("rfc7239") ++ trustedProxies("127.0.0.1") ++ trustForwardedHost(true),
        """
          |Forwarded: host=public.example;proto=https
        """.stripMargin
      )

      forwarding.authority.map(_.render) must beSome("public.example")
      forwarding.remote.identity mustEqual "127.0.0.1"
      forwarding.scheme mustEqual Scheme.Https
    }

    "stop scanning before entries preceding an rfc7239 host without for" in {
      val forwarding = forwardedRequestToLocalhost(
        version("rfc7239") ++ trustedProxies("127.0.0.1") ++ trustForwardedHost(true),
        """
          |Forwarded: for=203.0.113.43;host=client.example, host=proxy.example
        """.stripMargin
      )

      forwarding.authority.map(_.render) must beSome("proxy.example")
      forwarding.remote.identity mustEqual "127.0.0.1"
    }

    "not apply trustForwardedHost to x-forwarded headers" in {
      forwardedHostToLocalhost(
        version("x-forwarded") ++ trustedProxies("127.0.0.1") ++ trustForwardedHost(true),
        """
          |Forwarded: for=203.0.113.43;host=public.example
          |X-Forwarded-For: 203.0.113.43
        """.stripMargin
      ) must beNone
    }
  }
}
