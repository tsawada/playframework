/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.core.server.common

import java.net.InetAddress
import java.security.cert.X509Certificate

import scala.annotation.tailrec

import play.api.mvc.request.RemoteConnection
import play.api.mvc.request.RemoteConnection.RemoteNode
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
 * The ForwardedHeaderHandler class works out the remote identity, address, port, and protocol
 * by taking
 * into account Forward and X-Forwarded-* headers from trusted proxies. The
 * algorithm it uses is as follows:
 *
 * 1. Start with the immediate connection to the application.
 * 2. If the proxy *did not* send a valid Forward or X-Forward-* header then return
 *    that connection and don't do any further processing.
 * 3. If the proxy *did* send a valid header then work out whether we trust it by
 *    checking whether the immediate connection is in our list of trusted
 *    proxies or trusted RFC 7239 obfuscated proxy identifiers.
 * 4. If the immediate connection *is* a trusted proxy, then resume at step
 *    1 using the connection info in the forwarded header. If the forwarded
 *    identity is not an IP address, keep it as the selected remote identity
 *    and stop scanning unless it is an explicitly trusted obfuscated identifier.
 * 5. If the immediate connection *is not* a trusted proxy, then return the
 *    immediate connection info and don't do any further processing.
 *
 * Each identity is associated with a port and a secure or insecure protocol by
 * pairing it with a `port` and `proto` entry in the headers. If the `proto`
 * entry is missing, or if `proto` entries can't be matched with addresses,
 * then the default is insecure.
 * When `play.http.forwarded.trustSingleXForwardedProto` is enabled, a lone
 * `X-Forwarded-Proto` entry is associated with the client address instead of
 * being discarded.
 * When `play.http.forwarded.trustSingleXForwardedPort` is enabled, a lone
 * `X-Forwarded-Port` entry is associated with the client address instead of
 * being discarded.
 *
 * It is configured by these configuration options:
 * <dl>
 *   <dt>play.http.forwarded.version</dt>
 *   <dd>
 *     The version of the forwarded headers ist uses to parse the headers. It can be
 *     <code>x-forwarded</code> for legacy headers or
 *     <code>rfc7239</code> for the definition from the RFC 7239 <br>
 *     Default is x-forwarded.
 *   </dd>
 *   <dt>play.http.forwarded.trustedProxies</dt>
 *   <dd>
 *     A list of proxies that are ignored when getting the remote identity, remote address, or remote port.
 *     It can have optionally an address block size. When the address block size is set,
 *     all IP-addresses in the range of the subnet will be treated as trusted.
 *   </dd>
 *   <dt>play.http.forwarded.trustedProxyIdentifiers</dt>
 *   <dd>
 *     A list of RFC 7239 obfuscated proxy identifiers that are ignored when getting the
 *     remote identity. This only applies when using <code>rfc7239</code>.
 *   </dd>
 * </dl>
 */
private[server] class ForwardedHeaderHandler(configuration: ForwardedHeaderHandlerConfig) {

  /**
   * Update connection information based on any forwarding information in the headers.
   *
   * @param rawConnection The raw connection that connected to the Play server.
   * @param headers The request headers.
   * @return An updated connection
   */
  def forwardedConnection(rawConnection: RemoteConnection, headers: Headers): RemoteConnection = new RemoteConnection {
    // All public methods delegate to the lazily calculated connection info
    override def remoteAddress: InetAddress                           = parsed.remoteAddress
    override def remoteNode: RemoteNode                               = parsed.remoteNode
    override def byNode: Option[RemoteNode]                           = parsed.byNode
    override def remoteIpAddress: Option[InetAddress]                 = parsed.remoteIpAddress
    override def remotePort: Option[Int]                              = parsed.remotePort
    override def secure: Boolean                                      = parsed.secure
    override def clientCertificateChain: Option[Seq[X509Certificate]] = parsed.clientCertificateChain

    /**
     * Perform header parsing lazily, yielding a RemoteConnection with the results.
     */
    private lazy val parsed: RemoteConnection = {
      // Use a mutable iterator for performance when scanning the
      // header entries. Go through the headers in reverse order because
      // the nearest proxies will be at the end of the list and we need
      // to move backwards through the list to get to the original IP.
      val headerEntries: Iterator[ForwardedEntry] = configuration.forwardedHeaders(headers).reverseIterator

      @tailrec
      def scan(prev: RemoteConnection): RemoteConnection = {
        // Check if there's a forwarded header for us to scan.
        if (headerEntries.hasNext) {
          // There is a forwarded header from 'prev', so lets check if 'prev' is trusted.
          // If it's a trusted proxy then process the header, otherwise just use 'prev'.

          val previousFallbackAddress = configuration.trustedProxyFallbackAddress(prev)
          if (previousFallbackAddress.isDefined) {
            // 'prev' is a trusted proxy, so we process the next entry.
            val entry = headerEntries.next()
            configuration.parseEntry(entry, previousFallbackAddress) match {
              case Left(error) =>
                ForwardedHeaderHandler.logger.debug(
                  s"Error with info in forwarding header $entry, using $prev instead: $error."
                )
                prev
              case Right(parsedEntry) =>
                val connection = RemoteConnection(
                  parsedEntry.address,
                  parsedEntry.remoteNode,
                  parsedEntry.remotePort,
                  parsedEntry.byNode,
                  parsedEntry.secure,
                  None /* No cert chain for forward headers */
                )
                if (configuration.canContinueScanning(parsedEntry.remoteNode)) {
                  scan(connection)
                } else {
                  connection
                }
            }
          } else {
            // 'prev' is not a trusted proxy, so we don't scan ahead in the list of
            // forwards, we just return 'prev'.
            prev
          }
        } else {
          // No more headers to process, so just use its address.
          prev
        }
      }

      // Start scanning through connections starting at the rawConnection that
      // was made the Play server.
      scan(rawConnection)
    }
  }
}

private[server] object ForwardedHeaderHandler {
  private val logger = Logger(getClass)

  /**
   * The version of headers that this Play application understands.
   */
  sealed trait ForwardedHeaderVersion
  case object Rfc7239    extends ForwardedHeaderVersion
  case object Xforwarded extends ForwardedHeaderVersion

  type HeaderParser = Headers => Seq[ForwardedEntry]

  /**
   * An unparsed address, protocol, port, and receiving proxy node from a forwarded
   * header. Each value is optional.
   */
  final case class ForwardedEntry(
      addressString: Option[String],
      protoString: Option[String],
      portString: Option[String] = None,
      byString: Option[String] = None
  )

  /**
   * Basic information about an HTTP connection, parsed from a ForwardedEntry.
   * `remoteNode` is the selected forwarded identity. `fallbackAddress` is the
   * previous trusted proxy address used for legacy remote address APIs when the
   * selected identity is not an IP address.
   */
  final case class ParsedForwardedEntry(
      remoteNode: RemoteNode,
      fallbackAddress: Option[InetAddress],
      remotePort: Option[Int],
      secure: Boolean,
      byNode: Option[RemoteNode] = None
  ) {
    def remoteIpAddress: Option[InetAddress] = remoteNode match {
      case RemoteNode.Ip(address, _) => Some(address)
      case _                         => None
    }

    def address: InetAddress = remoteIpAddress.orElse(fallbackAddress).get
  }

  case class ForwardedHeaderHandlerConfig(
      version: ForwardedHeaderVersion,
      trustedProxies: List[Subnet],
      trustedProxyIdentifiers: Set[String] = Set.empty,
      trustSingleXForwardedProto: Boolean = false,
      trustSingleXForwardedPort: Boolean = false
  ) {
    val nodeIdentifierParser = new NodeIdentifierParser(version)

    /**
     * Removes surrounding quotes if present, otherwise returns original string.
     * Not RFC compliant. To be compliant we need proper header field parsing.
     */
    private def unquote(s: String): String = {
      if (s.length >= 2 && s.charAt(0) == '"' && s.charAt(s.length - 1) == '"') {
        s.substring(1, s.length - 1)
      } else s
    }

    private def parsePort(s: String): Option[Int] = {
      if (s.matches("\\d{1,5}")) {
        val port = s.toInt
        // Port numbers are 16-bit unsigned values.
        Option.when(port <= 65535)(port)
      } else {
        None
      }
    }

    private def portString(port: NodeIdentifierParser.Port): Option[String] = port match {
      case PortNumber(number)     => Some(number.toString)
      case ObfuscatedPort(string) => Some(string)
    }

    /**
     * Parse any Forward or X-Forwarded-* headers into a sequence of ForwardedEntry
     * objects. Each object a pair with an optional unparsed address and an
     * optional unparsed protocol. Further parsing may happen later, see `remoteConnection`.
     */
    def forwardedHeaders(headers: Headers): Seq[ForwardedEntry] = version match {
      case Rfc7239 => {
        val params = for {
          fhs <- headers.getAll("Forwarded")
          fh  <- fhs.split(",\\s*")
        } yield fh
          .split(";")
          .iterator
          .flatMap {
            _.span(_ != '=') match {
              case (_, "") => Option.empty[(String, String)] // no value

              case (rawName, v) => {
                // Remove surrounding quotes
                val name  = rawName.toLowerCase(java.util.Locale.ENGLISH)
                val value = unquote(v.tail)

                Some(name -> value)
              }
            }
          }
          .toMap

        params.map { (paramMap: Map[String, String]) =>
          ForwardedEntry(paramMap.get("for"), paramMap.get("proto"), byString = paramMap.get("by"))
        }
      }

      case Xforwarded =>
        def h(h: Headers, key: String) = h.getAll(key).flatMap(s => s.split(",\\s*")).map(unquote)
        val forHeaders                 = h(headers, "X-Forwarded-For")
        val protoHeaders               = h(headers, "X-Forwarded-Proto")
        val portHeaders                = h(headers, "X-Forwarded-Port")

        def protoForIndex(index: Int): Option[String] = {
          if (forHeaders.length == protoHeaders.length) {
            protoHeaders.lift(index)
          } else if (
            trustSingleXForwardedProto &&
            forHeaders.nonEmpty &&
            protoHeaders.length == 1 &&
            index == 0
          ) {
            protoHeaders.headOption
          } else {
            None
          }
        }

        def portForIndex(index: Int): Option[String] = {
          if (forHeaders.length == portHeaders.length) {
            portHeaders.lift(index)
          } else if (
            trustSingleXForwardedPort &&
            forHeaders.nonEmpty &&
            portHeaders.length == 1 &&
            index == 0
          ) {
            portHeaders.headOption
          } else {
            None
          }
        }

        forHeaders.zipWithIndex.map {
          case (f, index) => ForwardedEntry(Some(f), protoForIndex(index), portForIndex(index))
        }
    }

    /**
     * Try to parse a `ForwardedEntry` into a valid `ParsedForwardedEntry`
     * containing the remote identity, port, protocol security, and optional
     * fallback IP address. This method returns `Left` when the forwarded entry is
     * missing an address or the node identifier cannot be parsed.
     */
    def parseEntry(
        entry: ForwardedEntry,
        fallbackAddress: Option[InetAddress] = None
    ): Either[String, ParsedForwardedEntry] = {
      val byNode = entry.byString.flatMap { byString =>
        parseRemoteNode(byString).toOption
      }

      entry.addressString match {
        case None =>
          // We had a forwarding header, but it was missing the address entry for some reason.
          Left("No address")
        case Some(addressString) =>
          nodeIdentifierParser.parseNode(addressString) match {
            case Right((Ip(address), nodePort)) =>
              // IP identities can be checked against trustedProxies, so the caller may continue scanning.
              val secure     = entry.protoString.fold(false)(_ == "https") // Assume insecure by default
              val remotePort = entry.portString.flatMap(parsePort).orElse {
                nodePort.collect { case PortNumber(port) => port }
              }
              val connection =
                ParsedForwardedEntry(RemoteNode.Ip(address, remotePort), fallbackAddress, remotePort, secure, byNode)
              Right(connection)
            case Right((UnknownIp, nodePort)) =>
              // RFC 7239 allows "unknown" when the proxy cannot or does not want to disclose the node.
              val secure     = entry.protoString.fold(false)(_ == "https") // Assume insecure by default
              val connection =
                ParsedForwardedEntry(
                  RemoteNode.Unknown(nodePort.flatMap(portString)),
                  fallbackAddress,
                  None,
                  secure,
                  byNode
                )
              Right(connection)
            case Right((ObfuscatedIp(identifier), nodePort)) =>
              // RFC 7239 allows obfuscated identifiers such as "_hidden" to avoid disclosing the node.
              val secure     = entry.protoString.fold(false)(_ == "https") // Assume insecure by default
              val connection =
                ParsedForwardedEntry(
                  RemoteNode.Obfuscated(identifier, nodePort.flatMap(portString)),
                  fallbackAddress,
                  None,
                  secure,
                  byNode
                )
              Right(connection)
            case errorOrNonIp =>
              // The forwarding address entry couldn't be parsed for some reason.
              Left(s"Parse error: $errorOrNonIp")
          }
      }
    }

    private def parseRemoteNode(nodeString: String): Either[String, RemoteNode] = {
      nodeIdentifierParser.parseNode(nodeString).map {
        case (Ip(address), nodePort) =>
          RemoteNode.Ip(address, nodePort.collect { case PortNumber(port) => port })
        case (UnknownIp, nodePort) =>
          RemoteNode.Unknown(nodePort.flatMap(portString))
        case (ObfuscatedIp(identifier), nodePort) =>
          RemoteNode.Obfuscated(identifier, nodePort.flatMap(portString))
      }
    }

    /**
     * Check if a connection is considered to be a trusted proxy, i.e. a proxy whose
     * forwarding headers we will process.
     */
    def isTrustedProxy(address: InetAddress): Boolean = {
      trustedProxies.exists(_.isInRange(address))
    }

    /**
     * Return the fallback IP address to use when this connection is a trusted proxy.
     */
    def trustedProxyFallbackAddress(connection: RemoteConnection): Option[InetAddress] = connection.remoteNode match {
      case RemoteNode.Ip(address, _) if isTrustedProxy(address)                         => Some(address)
      case RemoteNode.Obfuscated(identifier, _) if isTrustedProxyIdentifier(identifier) =>
        Some(connection.remoteAddress)
      case _ => None
    }

    /**
     * Check if a forwarded node can be evaluated for trust in the next scan step.
     */
    def canContinueScanning(remoteNode: RemoteNode): Boolean = remoteNode match {
      case RemoteNode.Ip(_, _)                  => true
      case RemoteNode.Obfuscated(identifier, _) => isTrustedProxyIdentifier(identifier)
      case RemoteNode.Unknown(_)                => false
    }

    /**
     * Check if an RFC 7239 obfuscated identifier is considered to be a trusted proxy.
     */
    def isTrustedProxyIdentifier(identifier: String): Boolean = {
      version == Rfc7239 && trustedProxyIdentifiers.contains(identifier)
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
        config.get[Seq[String]]("trustedProxies").map(Subnet.apply).toList,
        config.getOptional[Seq[String]]("trustedProxyIdentifiers").getOrElse(Seq.empty).toSet,
        config.getOptional[Boolean]("trustSingleXForwardedProto").getOrElse(false),
        config.getOptional[Boolean]("trustSingleXForwardedPort").getOrElse(false)
      )
    }
  }
}
