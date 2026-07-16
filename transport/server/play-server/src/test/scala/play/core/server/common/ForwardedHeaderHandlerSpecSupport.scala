/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.common

import java.net.InetAddress

import com.google.common.net.InetAddresses
import org.specs2.mutable.Specification
import play.api.mvc.request.RemoteInfo
import play.api.mvc.request.RequestAuthority
import play.api.mvc.request.Scheme
import play.api.mvc.Headers
import play.api.Configuration
import play.core.server.common.ForwardedHeaderHandler._

private[common] trait ForwardedHeaderHandlerSpecSupport { self: Specification =>
  def handler(config: Map[String, Any]) =
    new ForwardedHeaderHandler(
      ForwardedHeaderHandlerConfig(Some(Configuration.from(config).withFallback(Configuration.reference)))
    )

  def forwardedResultToLocalhost(config: Map[String, Any], headersText: String): ForwardedResult =
    forwardedResult(forwardedRequestToLocalhost(config, headersText))

  def forwardedRequestToLocalhost(
      config: Map[String, Any],
      headersText: String,
      host: String = "localhost"
  ): ParsedForwarding =
    handler(config).forwardedRequest(
      RemoteInfo.ip("127.0.0.1", None),
      headers(headersText),
      Scheme.Http,
      RequestAuthority.parse(host).toOption
    )

  def forwardedResult(parsed: ParsedForwarding): ForwardedResult = ForwardedResult(parsed.remote, parsed.scheme)

  def expectedResult(address: String, isHttps: Boolean): ForwardedResult =
    expectedResult(InetAddresses.forString(address), isHttps)

  def expectedResult(remote: RemoteInfo, isHttps: Boolean): ForwardedResult =
    ForwardedResult(remote, if (isHttps) Scheme.Https else Scheme.Http)

  def expectedResult(address: InetAddress, isHttps: Boolean): ForwardedResult =
    expectedResult(RemoteInfo.ip(address, None), isHttps)

  def version(s: String) = {
    Map("play.http.forwarded.version" -> s)
  }

  def trustedProxies(s: String*) = {
    Map("play.http.forwarded.trustedProxies" -> s)
  }

  def headers(s: String): Headers = {
    def split(s: String, regex: String): Option[(String, String)] = s.split(regex, 2).toList match {
      case k :: v :: Nil => Some(k -> v)
      case _             => None
    }

    new Headers(s.split("\r?\n").toSeq.flatMap(split(_, ":\\s*")))
  }

  def processHeaders(
      config: Map[String, Any],
      headers: Headers
  ): Seq[(ForwardedEntry, Either[String, ParsedForwardedEntry], Option[Boolean], Option[Scheme])] = {
    val configuration = ForwardedHeaderHandlerConfig(Some(Configuration.from(config)))
    configuration.forwardedHeaders(headers).map { forwardedEntry =>
      val errorOrRemote = configuration.parseEntry(forwardedEntry)
      val trusted       = errorOrRemote match {
        case Left(_)      => None
        case Right(entry) => Some(configuration.isTrustedProxy(entry.remote.node))
      }
      (forwardedEntry, errorOrRemote, trusted, forwardedEntry.protoString.flatMap(Scheme.parse(_).toOption))
    }
  }

  def addr(ip: String): InetAddress = InetAddresses.forString(ip)

  val localhost: InetAddress = addr("127.0.0.1")
}

final case class ForwardedResult(remote: RemoteInfo, scheme: Scheme)
