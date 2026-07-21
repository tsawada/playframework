/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.common

import com.typesafe.config.ConfigMemorySize
import play.api.mvc.request.ClientCertificateInfo
import play.api.mvc.request.TransportConnection
import play.api.mvc.Headers
import play.api.Configuration
import play.core.server.common.ClientCertificateHeaderHandler._

/** Selects direct or trusted forwarded client-certificate metadata for a request. */
private[server] final class ClientCertificateHeaderHandler(configuration: Config) {

  def clientCertificate(transport: TransportConnection, headers: Headers): Option[ClientCertificateInfo] = {
    configuration.mode match {
      case Off                                                 => ClientCertificateInfo.fromTransport(transport)
      case Rfc9440 if !configuration.isTrustedProxy(transport) => ClientCertificateInfo.fromTransport(transport)
      case Rfc9440                                             =>
        Rfc9440ClientCertificateParser.parse(headers, configuration.limits) match {
          case Right(value) => value
          case Left(error)  =>
            throw new IllegalArgumentException(s"Forwarded client certificate limits exceeded: $error")
        }
    }
  }
}

private[server] object ClientCertificateHeaderHandler {
  sealed trait Mode
  case object Off     extends Mode
  case object Rfc9440 extends Mode

  final case class Config(mode: Mode, trustedProxies: List[Subnet], limits: ClientCertificateHeaderLimits) {
    def isTrustedProxy(transport: TransportConnection): Boolean =
      trustedProxies.exists(_.isInRange(transport.peer.address))
  }

  object Config {
    def apply(configuration: Option[Configuration]): Config = {
      val config = configuration
        .getOrElse(Configuration.reference)
        .get[Configuration]("play.http.forwarded.clientCertificates")

      val mode = config.get[String]("mode") match {
        case "off"     => Off
        case "rfc9440" => Rfc9440
        case _         =>
          throw config.reportError("mode", "Forwarded client certificate mode must be either off or rfc9440")
      }

      def positiveBytes(path: String): Long = {
        val value = config.get[ConfigMemorySize](path).toBytes
        if (value <= 0) throw config.reportError(path, "Forwarded client certificate size limits must be positive")
        value
      }

      val maxChainLength = config.get[Int]("limits.maxChainLength")
      if (maxChainLength < 0) {
        throw config.reportError("limits.maxChainLength", "maxChainLength must not be negative")
      }

      Config(
        mode,
        config.get[Seq[String]]("trustedProxies").map(Subnet.apply).toList,
        ClientCertificateHeaderLimits(
          positiveBytes("limits.maxHeaderBytes"),
          positiveBytes("limits.maxDecodedBytes"),
          positiveBytes("limits.maxCertificateBytes"),
          maxChainLength
        )
      )
    }
  }
}
