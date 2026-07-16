/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.common

import java.net.InetAddress
import java.util.regex.Pattern

import scala.annotation.tailrec

import play.api.mvc.request.RemoteInfo
import play.api.mvc.request.RequestAuthority
import play.api.mvc.request.Scheme
import play.api.http.HeaderNames
import play.api.mvc.Headers
import play.api.Configuration
import play.api.Logger
import play.core.server.common.NodeIdentifierParser.Ip
import ForwardedHeaderHandler._

/**
 * The ForwardedHeaderHandler class works out the remote address and protocol
 * by taking
 * into account Forward and X-Forwarded-* headers from trusted proxies. The
 * algorithm it uses is as follows:
 *
 * 1. Start with the immediate connection to the application.
 * 2. If the proxy *did not* send a valid Forward or X-Forward-* header then return
 *    that connection and don't do any further processing.
 * 3. If the proxy *did* send a valid header then work out whether we trust it by
 *    checking whether the immediate connection is in our list of trusted
 *    proxies.
 * 4. If the immediate connection *is* a trusted proxy, then resume at step
 *    1 using the connection info in the forwarded header.
 * 5. If the immediate connection *is not* a trusted proxy, then return the
 *    immediate connection info and don't do any further processing.
 *
 * Each address is associated with a secure or insecure protocol by pairing
 * it with a `proto` entry in the headers. If the `proto` entry is missing or
 * if `proto` entries can't be matched with addresses, then the default is
 * insecure.
 *
 * It is configured by two configuration options:
 * <dl>
 *   <dt>play.http.forwarded.version</dt>
 *   <dd>
 *     The version of the forwarded headers it uses to parse the headers. It can be
 *     <code>x-forwarded</code> for legacy headers or
 *     <code>rfc7239</code> for the definition from the RFC 7239 <br>
 *     Default is x-forwarded.
 *   </dd>
 *   <dt>play.http.forwarded.trustedProxies</dt>
 *   <dd>
 *     A list of proxies that are ignored when getting the remote address or the remote port.
 *     It can have optionally an address block size. When the address block size is set,
 *     all IP-addresses in the range of the subnet will be treated as trusted.
 *   </dd>
 * </dl>
 */
private[server] class ForwardedHeaderHandler(configuration: ForwardedHeaderHandlerConfig) {

  /** Update selected remote, scheme, and authority metadata from trusted forwarding headers. */
  def forwardedRequest(
      rawRemote: RemoteInfo,
      headers: Headers,
      scheme: Scheme,
      authority: Option[RequestAuthority]
  ): ParsedForwarding = {
    // Forwarding metadata cannot affect a request from an untrusted direct peer,
    // so avoid parsing attacker-controlled header lists that will be discarded.
    if (rawRemote.ipAddress.exists(configuration.isTrustedProxy)) {
      parse(rawRemote, headers, scheme, authority)
    } else {
      ParsedForwarding(rawRemote, scheme, authority)
    }
  }

  private def parse(
      rawRemote: RemoteInfo,
      headers: Headers,
      scheme: Scheme,
      authority: Option[RequestAuthority]
  ): ParsedForwarding = {
    val headerEntries = configuration.forwardedHeaders(headers).reverseIterator

    @tailrec
    def scan(previous: ParsedForwarding): ParsedForwarding = {
      if (headerEntries.hasNext && previous.remote.ipAddress.exists(configuration.isTrustedProxy)) {
        val entry = headerEntries.next()
        configuration.parseEntry(entry) match {
          case Left(error) =>
            ForwardedHeaderHandler.logger.debug(
              s"Error with info in forwarding header $entry, using $previous instead: $error."
            )
            previous
          case Right(parsedEntry) =>
            scan(
              ParsedForwarding(
                RemoteInfo.ip(parsedEntry.address, None),
                if (parsedEntry.secure) Scheme.Https else Scheme.Http,
                authority
              )
            )
        }
      } else previous
    }

    scan(ParsedForwarding(rawRemote, scheme, authority))
  }
}

private[server] object ForwardedHeaderHandler {
  private val logger              = Logger(getClass)
  private val XForwardedSeparator = Pattern.compile(",\\s*")

  /**
   * The version of headers that this Play application understands.
   */
  sealed trait ForwardedHeaderVersion
  case object Rfc7239    extends ForwardedHeaderVersion
  case object Xforwarded extends ForwardedHeaderVersion

  /**
   * An unparsed address and protocol pair from a forwarded header. Both values are
   * optional.
   */
  final case class ForwardedEntry(addressString: Option[String], protoString: Option[String])

  /**
   * Basic information about an HTTP connection, parsed from a ForwardedEntry.
   */
  final case class ParsedForwardedEntry(address: InetAddress, secure: Boolean)

  /** Effective request metadata selected by trusted forwarding information. */
  final case class ParsedForwarding(
      remote: RemoteInfo,
      scheme: Scheme,
      authority: Option[RequestAuthority]
  )

  case class ForwardedHeaderHandlerConfig(version: ForwardedHeaderVersion, trustedProxies: List[Subnet]) {
    val nodeIdentifierParser = new NodeIdentifierParser(version)

    /**
     * Removes surrounding quotes from legacy X-Forwarded values if present.
     * X-Forwarded headers have no common field-value grammar, so their established
     * permissive handling remains separate from the strict RFC 7239 parser.
     */
    private def stripQuotes(s: String): String = {
      if (s.length >= 2 && s.charAt(0) == '"' && s.charAt(s.length - 1) == '"') {
        s.substring(1, s.length - 1)
      } else s
    }

    /**
     * Parse any Forward or X-Forwarded-* headers into a sequence of ForwardedEntry
     * objects. Each object a pair with an optional unparsed address and an
     * optional unparsed protocol. Parameter-specific parsing happens while the
     * trusted proxy chain is scanned.
     */
    def forwardedHeaders(headers: Headers): Seq[ForwardedEntry] = version match {
      case Rfc7239 => {
        val headerValues = headers.getAll("Forwarded")
        val entries      = headerValues.flatMap { headerValue =>
          Rfc7239HeaderParser.parse(headerValue) match {
            case Right(elements) =>
              elements.map { paramMap =>
                ForwardedEntry(paramMap.get("for"), paramMap.get("proto"))
              }
            case Left(error) =>
              logger.debug(s"Invalid RFC 7239 Forwarded header, treating it as untrusted: $error")
              Seq(ForwardedEntry(None, None))
          }
        }
        // Forwarded uses 1#forwarded-element, so the combined field value must
        // contain an element even though empty HTTP list members are ignored.
        if (headerValues.nonEmpty && entries.isEmpty) Seq(ForwardedEntry(None, None)) else entries
      }

      case Xforwarded =>
        def h(h: Headers, key: String): Vector[String] =
          h.getAll(key)
            .iterator
            .flatMap(value => XForwardedSeparator.split(value).iterator)
            .map(_.trim)
            .map(stripQuotes)
            .toVector
        val forHeaders   = h(headers, HeaderNames.X_FORWARDED_FOR)
        val protoHeaders = h(headers, HeaderNames.X_FORWARDED_PROTO)
        if (forHeaders.length == protoHeaders.length) {
          forHeaders.lazyZip(protoHeaders).map { (f, p) =>
            ForwardedEntry(Some(f), Some(p))
          }
        } else {
          // If the lengths vary, then discard the protoHeaders because we can't tell which
          // proto matches which header. The connections will all appear to be insecure by
          // default.
          forHeaders.map {
            case f => ForwardedEntry(Some(f), None)
          }
        }
    }

    /**
     * Try to parse a `ForwardedEntry` into a valid `ConnectionInfo` with an IP address
     * and information about the protocol security. If this cannot happen, either because
     * parsing fails or because the connection info doesn't include an IP address, this
     * method will return `Left` with an error message.
     */
    def parseEntry(entry: ForwardedEntry): Either[String, ParsedForwardedEntry] = {
      entry.addressString match {
        case None =>
          // We had a forwarding header, but it was missing the address entry for some reason.
          Left("No address")
        case Some(addressString) =>
          nodeIdentifierParser.parseNode(addressString) match {
            case Right((Ip(address), _)) =>
              // Parsing was successful, use this connection and scan for another connection.
              val secure     = entry.protoString.fold(false)(_ == "https") // Assume insecure by default
              val connection = ParsedForwardedEntry(address, secure)
              Right(connection)
            case errorOrNonIp =>
              // The forwarding address entry couldn't be parsed for some reason.
              Left(s"Parse error: $errorOrNonIp")
          }
      }
    }

    /**
     * Check if a connection is considered to be a trusted proxy, i.e. a proxy whose
     * forwarding headers we will process.
     */
    def isTrustedProxy(address: InetAddress): Boolean = {
      trustedProxies.exists(_.isInRange(address))
    }
  }

  object ForwardedHeaderHandlerConfig {
    def apply(configuration: Option[Configuration]): ForwardedHeaderHandlerConfig = {
      val config = configuration.getOrElse(Configuration.reference).get[Configuration]("play.http.forwarded")

      val version = config.get[String]("version") match {
        case "x-forwarded" => Xforwarded
        case "rfc7239"     => Rfc7239
        case _             => throw config.reportError("version", "Forwarded header version must be either x-forwarded or rfc7239")
      }

      ForwardedHeaderHandlerConfig(version, config.get[Seq[String]]("trustedProxies").map(Subnet.apply).toList)
    }
  }
}
