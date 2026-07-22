/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.mvc;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import play.http.websocket.Message;

public class WebSocketTest {

  @Test
  public void compressionContextShouldEvaluateItsMessageLazilyAndOnlyOnce() {
    AtomicInteger evaluations = new AtomicInteger();
    WebSocket.CompressionContext context =
        new WebSocket.CompressionContext(
            () -> {
              evaluations.incrementAndGet();
              return new Message.Text("message");
            },
            7,
            true);

    assertEquals(7, context.payloadLength());
    assertEquals(true, context.isAboveCompressionThreshold());
    assertEquals(0, evaluations.get());

    assertEquals(new Message.Text("message"), context.message());
    assertEquals(new Message.Text("message"), context.message());
    assertEquals(1, evaluations.get());
  }
}
