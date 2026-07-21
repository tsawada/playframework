/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.common

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

import com.google.common.net.InetAddresses
import org.specs2.mutable.Specification
import play.api.http.HeaderNames
import play.api.mvc.request.ClientCertificateInfo
import play.api.mvc.request.ClientCertificateSource
import play.api.mvc.request.PeerEndpoint
import play.api.mvc.request.TransportConnection
import play.api.mvc.request.TransportTls
import play.api.mvc.Headers
import play.api.Configuration
import play.core.server.common.ClientCertificateHeaderHandler.Config
import play.core.server.common.ClientCertificateHeaderHandler.Off
import play.core.server.common.ClientCertificateHeaderHandler.Rfc9440

class ClientCertificateHeaderHandlerSpec extends Specification {
  private val limits = ClientCertificateHeaderLimits(
    maxHeaderBytes = 64 * 1024,
    maxDecodedBytes = 64 * 1024,
    maxCertificateBytes = 16 * 1024,
    maxChainLength = 8
  )

  private val leafBase64 =
    "MIIBqDCCAU6gAwIBAgIBBzAKBggqhkjOPQQDAjA6MRswGQYDVQQKDBJMZXQncyBB" +
      "dXRoZW50aWNhdGUxGzAZBgNVBAMMEkxBIEludGVybWVkaWF0ZSBDQTAeFw0yMDAx" +
      "MTQyMjU1MzNaFw0yMTAxMjMyMjU1MzNaMA0xCzAJBgNVBAMMAkJDMFkwEwYHKoZI" +
      "zj0CAQYIKoZIzj0DAQcDQgAE8YnXXfaUgmnMtOXU/IncWalRhebrXmckC8vdgJ1p" +
      "5Be5F/3YC8OthxM4+k1M6aEAEFcGzkJiNy6J84y7uzo9M6NyMHAwCQYDVR0TBAIw" +
      "ADAfBgNVHSMEGDAWgBRm3WjLa38lbEYCuiCPct0ZaSED2DAOBgNVHQ8BAf8EBAMC" +
      "BsAwEwYDVR0lBAwwCgYIKwYBBQUHAwIwHQYDVR0RAQH/BBMwEYEPYmRjQGV4YW1w" +
      "bGUuY29tMAoGCCqGSM49BAMCA0gAMEUCIBHda/r1vaL6G3VliL4/Di6YK0Q6bMje" +
      "SkC3dFCOOB8TAiEAx/kHSB4urmiZ0NX5r5XarmPk0wmuydBVoU4hBVZ1yhk="

  private val leaf = certificate(leafBase64)

  "ClientCertificateHeaderHandler" should {
    "ignore forwarded certificate fields when handling is off" in {
      val handler = new ClientCertificateHeaderHandler(Config(Off, Nil, limits))

      handler.clientCertificate(transport("127.0.0.1", Seq(leaf)), headers(HeaderNames.CLIENT_CERT -> ":bad:")) must
        beSome[ClientCertificateInfo].like {
          case info =>
            info.certificate must_== leaf
            info.source must_== ClientCertificateSource.DirectTransport
        }
    }

    "ignore forwarded certificate fields from an untrusted direct peer" in {
      val handler = rfcHandler("192.0.2.0/24")

      handler.clientCertificate(transport("127.0.0.1", Seq(leaf)), headers(HeaderNames.CLIENT_CERT -> ":bad:")) must
        beSome[ClientCertificateInfo].like {
          case info => info.source must_== ClientCertificateSource.DirectTransport
        }
    }

    "not mistake a trusted proxy certificate for the original client when fields are absent" in {
      val handler = rfcHandler("127.0.0.1")

      handler.clientCertificate(transport("127.0.0.1", Seq(leaf)), Headers()) must beNone
    }

    "select a trusted RFC 9440 client certificate" in {
      val handler = rfcHandler("127.0.0.1")

      handler.clientCertificate(
        transport("127.0.0.1"),
        headers(HeaderNames.CLIENT_CERT -> s":$leafBase64:")
      ) must beSome[ClientCertificateInfo].like {
        case info =>
          (info.certificate.getEncoded must beEqualTo(leaf.getEncoded))
            .and(info.source must_== ClientCertificateSource.Rfc9440)
      }
    }

    "validate configuration eagerly" in {
      Config(Some(config("play.http.forwarded.clientCertificates.mode" -> "rfc9440"))).mode must_== Rfc9440
      Config(Some(config("play.http.forwarded.clientCertificates.mode" -> "unknown"))) must throwA[Exception]
      Config(Some(config("play.http.forwarded.clientCertificates.trustedProxies" -> Seq("127.0.0.1/999")))) must
        throwA[Exception]
      Config(Some(config("play.http.forwarded.clientCertificates.limits.maxChainLength" -> -1))) must
        throwA[Exception]
    }
  }

  private def rfcHandler(trustedProxy: String): ClientCertificateHeaderHandler =
    new ClientCertificateHeaderHandler(Config(Rfc9440, List(Subnet(trustedProxy)), limits))

  private def transport(address: String, certificates: Seq[X509Certificate] = Seq.empty): TransportConnection =
    TransportConnection(
      PeerEndpoint(InetAddresses.forString(address), Some(443)),
      Option.when(certificates.nonEmpty)(TransportTls(certificates))
    )

  private def headers(values: (String, String)*): Headers = new Headers(values)

  private def certificate(base64: String): X509Certificate =
    CertificateFactory
      .getInstance("X.509")
      .generateCertificate(new ByteArrayInputStream(Base64.getDecoder.decode(base64)))
      .asInstanceOf[X509Certificate]

  private def config(values: (String, Any)*): Configuration =
    Configuration.from(values.toMap).withFallback(Configuration.reference)
}
