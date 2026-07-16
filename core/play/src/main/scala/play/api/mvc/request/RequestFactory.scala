/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.api.mvc.request

import jakarta.inject.Inject
import play.api.http.HttpConfiguration
import play.api.libs.crypto.CookieSignerProvider
import play.api.mvc._

/**
 * A `RequestFactory` provides logic for creating requests.
 */
trait RequestFactory

object RequestFactory

/**
 * The default [[RequestFactory]] used by a Play application. This
 * `RequestFactory` adds the following typed attributes to requests:
 * - request id (if not existing yet)
 * - cookie
 * - session cookie
 * - flash cookie
 */
class DefaultRequestFactory @Inject() (
    val cookieHeaderEncoding: CookieHeaderEncoding,
    val sessionBaker: SessionCookieBaker,
    val flashBaker: FlashCookieBaker
) extends RequestFactory {
  def this(config: HttpConfiguration) = this(
    new DefaultCookieHeaderEncoding(config.cookies),
    new DefaultSessionCookieBaker(config.session, config.secret, new CookieSignerProvider(config.secret).get),
    new DefaultFlashCookieBaker(config.flash, config.secret, new CookieSignerProvider(config.secret).get)
  )
}
