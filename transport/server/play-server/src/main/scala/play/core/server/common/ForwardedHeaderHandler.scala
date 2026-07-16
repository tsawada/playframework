/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.common

import java.util.regex.Pattern

import scala.annotation.tailrec

import play.api.http.HeaderNames
import play.api.mvc.request.ForwardingInfo
import play.api.mvc.request.ForwardingSource
import play.api.mvc.request.NodePort
import play.api.mvc.request.RemoteEndpoint
import play.api.mvc.request.RemoteInfo
import play.api.mvc.request.RemoteNode
import play.api.mvc.request.RequestAuthority
import play.api.mvc.request.Scheme
import play.api.mvc.Headers
import play.api.Configuration
import play.api.Logger
import play.core.server.common.NodeIdentifierParser.Ip
import play.core.server.common.NodeIdentifierParser.ObfuscatedIp
import play.core.server.common.NodeIdentifierParser.ObfuscatedPort
import play.core.server.common.NodeIdentifierParser.PortNumber
import play.core.server.common.NodeIdentifierParser.UnknownIp
import ForwardedHeaderHandler._

/**
 * Selects typed remote-node metadata from Forwarded and X-Forwarded headers
 * supplied by configured trusted IP proxies.
 *
 * RFC 7239 node identities, by nodes, and numeric or obfuscated node ports
 * are preserved without conflating selected remote metadata with the direct
 * transport connection.
 */
private[server] class ForwardedHeaderHandler(configuration: ForwardedHeaderHandlerConfig) {

  /** Update remote and scheme metadata in one trusted-proxy scan while preserving the request authority. */
  def forwardedRequest(
      rawRemote: RemoteInfo,
      headers: Headers,
      scheme: Scheme,
      authority: Option[RequestAuthority]
  ): ParsedForwarding = {
    // Forwarding metadata cannot affect a request from an untrusted direct peer,
    // so avoid parsing attacker-controlled header lists that will be discarded.
    if (configuration.isTrustedProxy(rawRemote.node)) {
      parse(rawRemote, headers, scheme, authority)
    } else {
      ParsedForwarding(rawRemote, scheme, authority)
    }
  }

  private def parse(
      rawRemote: RemoteInfo,
      headers: Headers,
      initialScheme: Scheme,
      initialAuthority: Option[RequestAuthority]
  ): ParsedForwarding = {
    // Use a mutable iterator for performance when scanning the
    // header entries. Go through the headers in reverse order because
    // the nearest proxies will be at the end of the list and we need
    // to move backwards through the list to get to the original IP.
    val headerEntries: Iterator[ForwardedEntry] = configuration.forwardedHeaders(headers).reverseIterator
    val forwardingSource                        = configuration.version match {
      case Rfc7239    => ForwardingSource.Rfc7239
      case Xforwarded => ForwardingSource.XForwarded
    }

    def result(
        remote: RemoteInfo,
        scheme: Scheme,
        authority: Option[RequestAuthority],
        acceptedPath: List[RemoteEndpoint]
    ): ParsedForwarding = {
      val selectedRemote = acceptedPath match {
        case _ :: via => remote.copy(forwarding = Some(ForwardingInfo(forwardingSource, via.toVector)))
        case Nil      => remote
      }
      ParsedForwarding(selectedRemote, scheme, authority)
    }

    @tailrec
    def scan(
        prev: RemoteInfo,
        scheme: Scheme,
        authority: Option[RequestAuthority],
        acceptedPath: List[RemoteEndpoint]
    ): ParsedForwarding = {
      // Check if there's a forwarded header for us to scan.
      if (headerEntries.hasNext) {
        // There is a forwarded header from 'prev', so lets check if 'prev' is trusted.
        // If it's a trusted proxy then process the header, otherwise just use 'prev'.

        if (configuration.isTrustedProxy(prev.node)) {
          // 'prev' is a trusted proxy, so we process the next entry.
          val entry = headerEntries.next()
          configuration.validateEntry(entry) match {
            case Left(error) =>
              ForwardedHeaderHandler.logger.debug(
                s"Error with info in forwarding header $entry, using $prev instead: $error."
              )
              result(prev, scheme, authority, acceptedPath)
            case Right(parsedEntry) =>
              val selectedScheme =
                if (entry.protoString.contains("https")) Scheme.Https else Scheme.Http
              parsedEntry match {
                case None =>
                  ForwardedHeaderHandler.logger.debug(
                    s"Error with info in forwarding header $entry, using $prev instead: No address."
                  )
                  result(prev, scheme, authority, acceptedPath)
                case Some(value) =>
                  val selectedPath = value.remote.endpoint :: acceptedPath
                  if (configuration.canContinueScanning(value.remote.node)) {
                    scan(value.remote, selectedScheme, authority, selectedPath)
                  } else {
                    result(value.remote, selectedScheme, authority, selectedPath)
                  }
              }
          }
        } else {
          // 'prev' is not a trusted proxy, so we don't scan ahead in the list of
          // forwards, we just return 'prev'.
          result(prev, scheme, authority, acceptedPath)
        }
      } else {
        // No more headers to process, so just use its address.
        result(prev, scheme, authority, acceptedPath)
      }
    }

    // Start scanning through selected remote nodes at the direct peer that
    // connected to the Play server.
    scan(
      rawRemote,
      initialScheme,
      initialAuthority,
      acceptedPath = Nil
    )
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
   * Unparsed remote and request metadata from a forwarded header.
   * Each value is optional.
   */
  final case class ForwardedEntry(
      addressString: Option[String],
      protoString: Option[String],
      byString: Option[String] = None
  )

  final case class ParsedForwarding(
      remote: RemoteInfo,
      scheme: Scheme,
      authority: Option[RequestAuthority]
  )

  /**
   * Selected remote metadata parsed from a ForwardedEntry.
   */
  final case class ParsedForwardedEntry(remote: RemoteInfo)

  case class ForwardedHeaderHandlerConfig(
      version: ForwardedHeaderVersion,
      trustedProxies: List[Subnet]
  ) {
    val nodeIdentifierParser = new NodeIdentifierParser(version)

    private def stripQuotes(s: String): String = {
      if (s.length >= 2 && s.charAt(0) == '"' && s.charAt(s.length - 1) == '"') {
        s.substring(1, s.length - 1)
      } else s
    }

    private def nodePort(port: NodeIdentifierParser.Port): NodePort = port match {
      case PortNumber(number)     => NodePort.Numeric(number)
      case ObfuscatedPort(string) => NodePort.Obfuscated(string)
    }

    /**
     * Parse any Forward or X-Forwarded-* headers into a sequence of ForwardedEntry
     * objects containing optional unparsed remote and request metadata.
     * Parameter-specific parsing happens while the trusted proxy chain is scanned.
     * A malformed RFC 7239 field produces an empty entry so trusted-proxy scanning
     * stops at that field.
     */
    def forwardedHeaders(headers: Headers): Seq[ForwardedEntry] = version match {
      case Rfc7239 => {
        val headerValues = headers.getAll("Forwarded")
        val entries      = headerValues.flatMap { headerValue =>
          Rfc7239HeaderParser.parse(headerValue) match {
            case Right(elements) =>
              elements.map { paramMap =>
                ForwardedEntry(
                  paramMap.get("for"),
                  paramMap.get("proto"),
                  byString = paramMap.get("by")
                )
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
        // Headers may expose a linear Seq. Materialize indexed values once because
        // protocol entries are looked up by their corresponding address index.
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
          forHeaders.lazyZip(protoHeaders).map { (forwardedFor, forwardedProto) =>
            ForwardedEntry(Some(forwardedFor), Some(forwardedProto))
          }
        } else {
          forHeaders.map(forwardedFor => ForwardedEntry(Some(forwardedFor), None))
        }
    }

    /**
     * Try to parse a `ForwardedEntry` into a valid `ParsedForwardedEntry`
     * containing the remote identity and port. This method returns `Left` when
     * the forwarded entry is missing an address or either node identifier cannot be parsed.
     */
    def parseEntry(entry: ForwardedEntry): Either[String, ParsedForwardedEntry] = {
      for {
        byNode        <- parseByNode(entry)
        addressString <- entry.addressString.toRight("No address")
        remoteNode    <- parseRemoteNode(addressString).left.map(error => s"Invalid for parameter: $error")
      } yield ParsedForwardedEntry(RemoteInfo(remoteNode, byNode))
    }

    def validateEntry(entry: ForwardedEntry): Either[String, Option[ParsedForwardedEntry]] = {
      entry.addressString match {
        case Some(_) => parseEntry(entry).map(Some(_))
        case None    => parseByNode(entry).map(_ => None)
      }
    }

    private def parseByNode(entry: ForwardedEntry): Either[String, Option[RemoteNode]] = {
      entry.byString match {
        case Some(byString) =>
          parseRemoteNode(byString).left.map(error => s"Invalid by parameter: $error").map(Some(_))
        case None => Right(None)
      }
    }

    private def parseRemoteNode(nodeString: String): Either[String, RemoteNode] = {
      nodeIdentifierParser.parseNode(nodeString).map {
        case (Ip(address), nodePort) =>
          RemoteNode.Ip(address, nodePort.map(this.nodePort))
        case (UnknownIp, nodePort) =>
          RemoteNode.Unknown(nodePort.map(this.nodePort))
        case (ObfuscatedIp(identifier), nodePort) =>
          RemoteNode.Obfuscated(identifier, nodePort.map(this.nodePort))
      }
    }

    /** Check whether a selected remote node may supply trusted forwarding metadata. */
    def isTrustedProxy(node: RemoteNode): Boolean = node match {
      case RemoteNode.Ip(address, _) => trustedProxies.exists(_.isInRange(address))
      case _                         => false
    }

    /**
     * Check if a forwarded node can be evaluated for trust in the next scan step.
     */
    def canContinueScanning(remoteNode: RemoteNode): Boolean = remoteNode match {
      case RemoteNode.Ip(_, _) => true
      case _                   => false
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

      ForwardedHeaderHandlerConfig(
        version,
        config.get[Seq[String]]("trustedProxies").map(Subnet.apply).toList
      )
    }
  }
}
