/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.http.websocket

import org.specs2.mutable.Specification

class CloseCodesSpec extends Specification {
  "CloseCodes" should {
    "provide the registered service close codes" in {
      (CloseCodes.ServiceRestart, CloseCodes.TryAgainLater, CloseCodes.BadGateway) must_== ((1012, 1013, 1014))
    }
  }
}
