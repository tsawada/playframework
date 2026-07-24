/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.mvc

import org.specs2.mutable.Specification
import play.api.http.websocket.TextMessage

class WebSocketSpec extends Specification {

  "WebSocket.CompressionContext" should {
    "evaluate its message lazily and only once" in {
      var evaluations = 0
      val context     = new WebSocket.CompressionContext(
        {
          evaluations += 1
          TextMessage("message")
        },
        payloadLength = 7,
        isAboveCompressionThreshold = true
      )

      context.payloadLength must_== 7
      context.isAboveCompressionThreshold must beTrue
      evaluations must_== 0

      context.message must_== TextMessage("message")
      context.message must_== TextMessage("message")
      evaluations must_== 1
    }
  }
}
