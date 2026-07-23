/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.http.websocket

/**
 * WebSocket close codes.
 *
 * RFC 6455 limits the reason in a Close frame with a status code to 123 UTF-8 bytes. Play truncates longer
 * Close reasons before sending them.
 *
 * See the [[https://www.iana.org/assignments/websocket/websocket.xhtml IANA WebSocket Close Code Number Registry]]
 * for the registered close codes.
 */
object CloseCodes {
  val Regular       = 1000
  val GoingAway     = 1001
  val ProtocolError = 1002
  val Unacceptable  = 1003

  /**
   * Indicates that no close status code was present.
   *
   * This value is reserved by RFC 6455 and must not be sent as the status code in a WebSocket Close frame.
   */
  val NoStatus = 1005

  /**
   * Indicates that the connection was closed abnormally without receiving a Close frame.
   *
   * This value is reserved by RFC 6455 and must not be sent as the status code in a WebSocket Close frame.
   */
  val ConnectionAbort        = 1006
  val InconsistentData       = 1007
  val PolicyViolated         = 1008
  val TooBig                 = 1009
  val ClientRejectsExtension = 1010
  val UnexpectedCondition    = 1011

  /**
   * Indicates that the service is restarting and the client may reconnect.
   */
  val ServiceRestart = 1012

  /**
   * Indicates that the service is temporarily unavailable and the client may reconnect later.
   */
  val TryAgainLater = 1013

  /**
   * Indicates that a server acting as a gateway received an invalid response from its upstream server.
   */
  val BadGateway = 1014

  /**
   * Indicates that the connection was closed because the TLS handshake failed.
   *
   * This value is reserved by RFC 6455 and must not be sent as the status code in a WebSocket Close frame.
   */
  val TLSHandshakeFailure = 1015
}
