<!--- Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com> -->

# Play 3.1 Migration Guide

TBD

## How to migrate

Before starting `sbt`, make sure to make the following upgrades.

### Play upgrade

TBD

### sbt upgrade

TBD

### Minimum required Java and sbt version

TBD

### Play upgraded to Pekko 2 and Pekko HTTP 2

Play now uses Pekko 2 and Pekko HTTP 2. If your build overrides Play's Pekko dependencies, align those overrides with the Pekko 2 and Pekko HTTP 2 versions used by this Play release and review the upstream Pekko migration notes for any APIs you use directly.

The deprecated low-level `org.apache.pekko.http.play.WebSocketHandler.handleWebSocket` overloads that accepted Pekko HTTP's old `UpgradeToWebSocket` API have been removed. Code using this internal Pekko HTTP bridge should use the maintained `WebSocketUpgrade` overload instead.

### Remote connection APIs replaced by typed remote and transport metadata

Play now supports RFC 7239 `Forwarded` remote identifiers that are not IP addresses, such as `for=unknown` and obfuscated identifiers like `for=_hidden`. This change removes the legacy `RemoteConnection`, `connection`, `remoteAddress`, and request certificate-chain APIs because they mixed selected forwarding metadata with facts about the socket terminating at Play.

This is an intentional source- and binary-incompatible public API change. Applications and libraries compiled against the removed types or methods must be migrated and recompiled for this Play release.

Use `RequestHeader.remote` when you need the structured identity selected from trusted forwarded headers. Its `node` is a `RemoteNode`, `identity` renders that node, and `ipAddress` contains an address only when the selected node is an IP identity.

Use `RequestHeader.transport.peer` when you need the socket peer that connected directly to Play, including its source port. Actual proxy-to-Play TLS and peer certificates are available through `RequestHeader.transport.tls`. These transport values remain unchanged when trusted forwarding selects a different logical remote node.

Java request accessors migrate as follows:

| Removed API | Replacement |
| --- | --- |
| `Http.RequestHeader.remoteAddress()` | Use `remote().identity()` for the complete selected identity, which can also be `unknown` or obfuscated. Use `remote().ipAddress()` when the application specifically requires an IP address. |
| `Http.RequestHeader.clientCertificateChain()` | Use `transport().tls().map(Http.TransportTls::peerCertificates)` for certificates presented on the connection directly terminating at Play. |

Java test builders replace the former connection accessors and setters with the corresponding typed values:

| Removed API | Replacement |
| --- | --- |
| `RequestBuilder.remoteAddress()` and `remoteAddress(...)` | Use `remote()` and `remote(...)` with a `RemoteInfo` containing a typed `RemoteNode`. |
| `RequestBuilder.clientCertificateChain()` and `clientCertificateChain(...)` | Use `transport()` and `transport(...)` with certificates stored in `TransportTls`. |

For example:

```java
InetAddress remoteAddress = InetAddress.getByName("192.0.2.10");
Http.RemoteInfo remote =
    new Http.RemoteInfo(
        new Http.RemoteNode.Ip(
            remoteAddress, Optional.of(new Http.NodePort.Numeric(53124))),
        Optional.empty());
Http.PeerEndpoint peer =
    new Http.PeerEndpoint(InetAddress.getByName("127.0.0.1"), Optional.of(44000));
Http.TransportConnection transport =
    new Http.TransportConnection(
        peer, Optional.of(new Http.TransportTls(certificates)));

Http.RequestBuilder request =
    new Http.RequestBuilder()
        .remote(remote)
        .transport(transport)
        .scheme(Http.Scheme.HTTPS);
```

The full Scala `FakeRequest.apply` overload likewise replaces `remoteAddress`, `secure`, and `clientCertificateChain` with `remote`, `scheme`, and `transport`. Code that previously changed the combined value through `withConnection` should now change only the relevant dimension with `withRemote`, `withScheme`, or `withTransport`:

```scala
val remote = RemoteInfo.ip(
  InetAddress.getByName("192.0.2.10"),
  Some(NodePort.Numeric(53124))
)
val peer      = PeerEndpoint(InetAddress.getByName("127.0.0.1"), Some(44000))
val transport = TransportConnection(peer, Some(TransportTls(certificates)))

val request = FakeRequest(GET, "/")
  .withRemote(remote)
  .withTransport(transport)
  .withScheme(Scheme.Https)
```

For example, with a trusted direct proxy at `127.0.0.1`:

```http
Forwarded: for=_hidden;proto=https
```

Play reports:

```scala
request.remote.node      // RemoteNode.Obfuscated("_hidden", None)
request.remote.identity  // "_hidden"
request.remote.ipAddress // None
request.remote.nodePort  // None, or a numeric/obfuscated NodePort
request.secure           // true
```

### IP filter entries match typed remote identities

The IP filter now evaluates `RequestHeader.remote.node` rather than only its optional IP projection. The existing `play.filters.ip.whiteList` and `blackList` keys accept numeric IPv4/IPv6 literals, CIDR networks, `unknown`, and exact RFC 7239 obfuscated identifiers such as `_edge`.

A nonempty whitelist remains fail-closed and allows only matching nodes. When the whitelist is empty, a blacklist now denies only matching nodes. Consequently, an unrelated IP blacklist entry no longer implicitly rejects an `unknown` or obfuscated selected remote; list that identity explicitly or use a whitelist when unlisted identities must be denied. If both lists are nonempty, the existing whitelist precedence is unchanged.

List parsing is now strict, accepts only printable ASCII, and performs no DNS lookup. IPv4 entries must use canonical four-part dotted-decimal notation, with no leading zero unless an octet is exactly `0`.

The former `InetAddress.getByName` parser accepted more than numeric IP literals. Replace DNS names such as `localhost`, shorthand IPv4 such as `127.1`, integer IPv4 such as `2130706433`, and leading-zero IPv4 such as `001.002.003.004` with canonical numeric literals. Remove brackets from IPv6 literals, and remove or replace IPv6 zone identifiers; acceptance of scoped addresses depended on the local interfaces. An empty entry previously resolved to the loopback address and must now be written explicitly. Values such as `unknown` and `_edge` were formerly passed to name resolution and could match a resolved IP; they now mean the RFC 7239 unknown identity and an exact obfuscated identity, respectively.

Whitespace-padded entries and endpoint notation such as `192.0.2.1:443` were already invalid under the former parser and remain invalid. Non-ASCII values, malformed identities, and invalid CIDR prefixes are also rejected at startup. Matching uses only the selected node identity: its port, RFC `by` node, and direct transport peer do not affect the result.

`play.http.forwarded.trustedProxies` now likewise accepts only ASCII numeric IPv4/IPv6 literals and CIDR networks. Scoped IPv6 spellings such as `fe80::1%1` and addresses written with non-ASCII decimal digits now fail application startup instead of silently discarding a scope or normalizing the digits.

### Request scheme and authority are first-class values

`RequestHeader.scheme` and `RequestHeader.authority` now carry the effective destination metadata independently from the remote client and direct transport. `secure`, `host`, `domain`, and the exposed `Host` header are derived from those values. Custom Scala `RequestHeader` implementations must provide both fields, and custom `RequestFactory` implementations must accept and preserve them.

Immutable core request-copy operations no longer infer authority from a changed target or let generic header replacement create contradictory host state. Use `withScheme` and `withAuthority` in Scala when changing these values deliberately. Core `RequestHeader.withHeaders` may omit the canonical `Host`, but a conflicting or duplicate `Host` is rejected.

The request-building helpers retain a convenient Host mutation path. Scala `FakeRequest.withHeaders` and Java `RequestBuilder.headers(...)` or `header(...)` treat exactly one case-insensitive `Host` value as an authority replacement, parse and canonicalize it, and keep the typed authority and exposed header synchronized. Omitting `Host` preserves the existing authority; duplicate or invalid values throw `IllegalArgumentException`. Java builders can also use `scheme(...)` and `authority(...)` directly.

Java `RequestBuilder.uri(...)` now changes only the synthetic request target. Code that previously relied on it to change the effective scheme or host must set those values explicitly.

For requests received by a Play server, an absolute-form target supplies its scheme and any authority it contains. A trusted gateway may preserve the public absolute target or rewrite it to the scheme of its backend connection; Play accepts either when the target scheme matches the final effective forwarded scheme or the direct transport scheme. The trusted forwarded scheme becomes the application-facing `RequestHeader.scheme`, while `RequestHeader.uri` retains the raw target. An accepted HTTP or HTTPS absolute-form target with an empty path now exposes `/` through `RequestHeader.path`, while its raw target and query remain unchanged. Play rejects an absolute scheme that describes neither side of the trusted hop. Consequently, a client cannot make a plaintext connection secure merely by writing `https://` without accepted scheme-changing forwarding metadata. When a rejected request is reported to a custom `HttpErrorHandler`, the best-effort `RequestHeader` supplied for error handling can retain the raw absolute target alongside independently valid forwarding metadata.

Play now rejects an HTTP/1.1 request with a missing, duplicate, or syntactically invalid `Host` field before applying absolute-form or CONNECT authority precedence. Origin-form and asterisk-form requests require a non-empty Host authority. Absolute-form and CONNECT requests may carry an empty `Host` field because their request target supplies the effective authority; Play exposes that target authority as the canonical `Host`. HTTP and HTTPS absolute targets always require a non-empty authority. Other absolute schemes are rejected as soon as their scheme is recognized without parsing the remaining URI. HTTP/1.0 requests retain missing-Host compatibility when the selected server backend accepts them. CONNECT now accepts only authority-form targets with a non-empty host and a destination port from `1` through `65535`; port zero, oversized ports, and absolute-form CONNECT targets are rejected. Netty passes this absolute-form traffic through to Play. Pekko HTTP currently rejects an empty or different `Host` while establishing its effective URI, before Play can apply target precedence.

### Missing, invalid, or ambiguous X-Forwarded-Proto retains the last verified scheme

When Play accepts an `X-Forwarded-For` identity but cannot associate it with a valid `X-Forwarded-Proto` value, Play now retains the last verified effective scheme. This applies when the proto value is missing or invalid, and when the `X-Forwarded-For` and `X-Forwarded-Proto` lists cannot be paired according to the configured single-proto policy. The selected remote identity can still change; unverified protocol metadata no longer forces that identity to be insecure.

For example, suppose a trusted proxy connects to Play over TLS and sends:

```http
X-Forwarded-For: 203.0.113.43
```

with no `X-Forwarded-Proto`. Play 3.0 selected the forwarded client address but projected the request as insecure, so `request.secure` was `false`. Play now selects the same remote identity while retaining the HTTPS scheme verified from the proxy-to-Play transport, so `request.scheme == Scheme.Https` and `request.secure == true`.

This can change redirect and HSTS behavior, absolute and WebSocket URL generation, CORS same-origin decisions, and application policy that reads `request.secure` or `request.scheme`. If the public client scheme can differ from the proxy-to-Play transport scheme, configure the trusted proxy to emit a valid, correctly aligned `X-Forwarded-Proto` value; otherwise Play can only retain the last scheme it has verified.

### CORS same-origin checks use effective request metadata

The CORS filter now compares the `Origin` header with the request's normalized effective scheme, host, and port from `RequestHeader.scheme` and `RequestHeader.authority`. An omitted port is equivalent to port `80` for HTTP or `443` for HTTPS. For example, `http://www.example.com` and `http://www.example.com:80` are now the same origin, as are the corresponding HTTPS forms with port `443`.

Accepted trusted forwarding metadata can therefore change the request origin used by CORS. A trusted forwarded scheme, host, or port can make the public origin match even when the direct proxy-to-Play scheme or internal `Host` differs. The CORS filter does not read `Forwarded` or `X-Forwarded-*` fields directly: only metadata accepted from a configured trusted proxy through enabled forwarding options affects the comparison. Untrusted, disabled, or invalid forwarding metadata has no CORS effect. Review CORS behavior during upgrade if your deployment exposes different public and internal origins, and see [[configuring trusted proxies|HTTPServer#configuring-trusted-proxies]].

### RFC 7239 Forwarded header syntax is validated

Play now validates RFC 7239 `Forwarded` field values before using them. Parameter names must be valid tokens, values must be tokens or quoted strings, and a parameter cannot occur more than once in one forwarded element. Empty HTTP list elements remain accepted and are ignored.

RFC 7239 requires IPv6 addresses and node identifiers with ports to be quoted because they contain `:`:

```http
Forwarded: for="[2001:db8:cafe::17]:4711"
Forwarded: for="192.0.2.43:4711"
```

For compatibility with Play 3.0, Play continues to accept these values without quotes in the `for` parameter. Play applies the same allowance to `by` for consistent node parsing. Play also accepts an optional sequence of spaces and tabs immediately after the semicolon between parameters, as in `for=192.0.2.43; proto=https`. Whitespace before the semicolon or around `=` remains invalid. These are bounded compatibility allowances; proxy configurations should emit quoted node values and the compact RFC syntax without parameter-separator whitespace. Other parameters do not receive the unquoted-value allowance.

Play also validates every recognized value in an otherwise syntactically valid element: `for` and `by` must be valid node identifiers, `host` must conform to request-authority syntax, and `proto` must be a valid RFC 3986 scheme. This validation is atomic and is performed even when a feature such as forwarded-host application is disabled. If any recognized value is invalid, Play applies none of that element, stops scanning older elements, and keeps the last verified remote, scheme, and authority. Syntactically valid unknown extension parameters remain ignored.

The referenced `Host` grammar permits a registered name with zero characters, including a port-only value such as `host=":8080"`. Such a value remains grammatically valid but cannot establish Play's effective HTTP authority. When forwarded-host application is enabled, Play ignores that authority as a unit, including its embedded port, retains the last verified authority, and still applies the element's other valid metadata. The same non-empty-host requirement applies to `X-Forwarded-Host`. In `x-forwarded` mode, a separately trusted `X-Forwarded-Port` remains independent and can update a retained non-empty authority.

When Play encounters a malformed `Forwarded` field while scanning a trusted proxy chain, it likewise stops at that field and keeps the last verified request information. Check that each trusted proxy emits valid RFC 7239 syntax and recognized values before upgrading.

### Redirect HTTPS filter only reads X-Forwarded-Proto when enabled

The Redirect HTTPS filter previously treated `X-Forwarded-Proto: https` as a secure request even when `play.filters.https.xForwardedProtoEnabled` was `false`. The filter now ignores `X-Forwarded-Proto` unless that legacy option is explicitly enabled. Deployments that relied on this implicit header handling may otherwise repeatedly redirect a public HTTPS request when a proxy terminates HTTPS but Play still sees the proxy connection as insecure.

Applications behind a trusted proxy should normally configure [[trusted proxies|HTTPServer#configuring-trusted-proxies]] so Play derives `request.secure` after validating the forwarded proxy chain. If a trusted proxy sends a single `X-Forwarded-Proto` value without `X-Forwarded-For`, enable:

```hocon
play.http.forwarded.trustXForwardedProtoWithoutXForwardedFor = true
```

The immediate proxy must also be included in `play.http.forwarded.trustedProxies`. Applications that intentionally rely on the filter reading `X-Forwarded-Proto` directly can instead set:

```hocon
play.filters.https.xForwardedProtoEnabled = true
```

This legacy option changes more than the secure-request check: the filter redirects only requests with `X-Forwarded-Proto: http`. For an otherwise insecure request, a missing or unexpected value causes the filter to pass the request to the application without an HTTPS redirect or HSTS header, and the option does not update `request.secure`. Only enable it when bypassing redirects for requests without the header is intentional, or when a trusted proxy removes or overwrites every client-supplied value and reliably sends either `http` or `https`.

### HEAD responses no longer include generated Content-Length headers

Play no longer renders generated `Content-Length` headers for `HEAD` responses. `HEAD` responses still do not include a response body, but applications and tests should not rely on `Content-Length` being present on a `HEAD` response, even when the equivalent `GET` response has a known length.

This behavior follows Pekko HTTP 2, which changed generated `Content-Length` rendering in [apache/pekko-http#962](https://github.com/apache/pekko-http/pull/962), ported from [akka/akka-http#4214](https://github.com/akka/akka-http/pull/4214). The original upstream change fixed response framing for statuses such as `205 Reset Content` and made `Content-Length` rendering depend on the request method and response status.

If your tests compare `HEAD` and `GET` response headers, exclude `Content-Length` from that comparison. If your application needs to expose resource size metadata for `HEAD` requests, use an application-specific header.

### Clustered Pekko applications may require additional JVM add-opens

Applications that configure the Play ActorSystem as a Pekko cluster, including applications using Play's cluster-sharding module, start Pekko Remote/Artery TCP. Artery TCP depends on Agrona, which accesses `jdk.internal.misc.Unsafe` on the JVM.

On strongly encapsulated JDKs this can require the following JVM option:

```text
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
```

Add this option to the JVM that runs the application or tests when using clustered Pekko/Artery TCP and you see an access failure involving `org.agrona.UnsafeApi` and `jdk.internal.misc.Unsafe`.

### Java form binding no longer depends on Spring Framework libraries

Historically, Play's Java form binding used Spring Framework libraries, going back to the beginning of Play 2. Starting with this release, Play owns the form binding code it needs internally and registers the supported default conversions through Play's `Formatters` infrastructure.

This removes Spring from the application classpath for Java form binding and gives Play direct control over the binding behavior. The old integration inherited behavior that was useful for Spring bean configuration, but surprising for Play web forms: classpath scanning and factory lookup during JavaBean introspection, convention-based converter class loading (so called "editors" in Spring), resource location handling, class loading, and default converters that could resolve files, URLs, classpath resources, streams, or readers from submitted form values.

For Play applications, form binding should convert submitted request strings into application data values. It should not, by default, interpret user input as Spring resource expressions, open resources, inspect the classpath, or load classes. This avoids surprising resource access from user-submitted form data, such as opening streams or readers, resolving classpath resources, or loading classes during form binding.

As a result, the following types and Spring-specific behaviors are no longer bound by default:

* `java.io.File`
* `java.nio.file.Path`
* `java.io.InputStream`
* `java.io.Reader`
* `org.xml.sax.InputSource`
* `java.lang.Class`
* `java.lang.Class[]`
* Raw `java.lang.Enum` targets. Concrete enum types continue to bind by enum constant name.
* Spring resource types and resource patterns, if Spring is present in the application
* Spring-style resource locations such as `classpath:` URL/resource binding

Plain `URI` values are still parsed as URI values. For example, a `classpath:` URI is treated as ordinary URI text and is not resolved as a classpath resource by Play.

`URL` values are parsed as regular URLs only. Spring-style `classpath:` URL/resource binding is not supported.

This does not affect normal file uploads. Play file uploads use multipart form handling and `Http.MultipartFormData.FilePart`, not string-to-`File` form binding. See [[Handling file upload|JavaFileUpload]] for the Java file upload API.

If your application intentionally needs one of the removed bindings, register an explicit formatter or converter for that type in your application. See [[Register a custom DataBinder|JavaForms#Register-a-custom-DataBinder]] for the Java form formatter setup. If you think a removed binding should be supported by Play by default, please open an issue in the [Play issue tracker](https://github.com/playframework/playframework/issues).

### Raw WebSocket handlers now receive status 1006 for abnormal connection loss

Raw WebSocket handlers that consume `play.api.http.websocket.Message` values now receive `CloseMessage(Some(1006), ...)` when the underlying connection closes or fails without Play receiving a WebSocket Close frame.

This can happen when the network connection is interrupted, the client disconnects without completing the WebSocket close handshake, or the server idle timeout closes the transport. The status code `1006` is not sent on the wire; it is only delivered to application code to report that the connection was closed abnormally. This is the behavior defined by [RFC 6455 section 7.1.5](https://datatracker.ietf.org/doc/html/rfc6455#section-7.1.5) and the reserved close code definition in [section 7.4.1](https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1).

Because abnormal WebSocket termination is now reported as a `CloseMessage` before the stream completes, raw `Message` handlers that previously relied on `watchTermination` seeing a failed stream for transport loss should instead inspect `CloseMessage(Some(1006), ...)`.

Scala
: @[abnormal-closure](code/WebSocketCloseMigration.scala)

Java
: @[abnormal-closure](code/WebSocketCloseMigration.java)

Handlers using typed APIs such as `WebSocket.accept[String, String]` still do not receive close control frames as typed messages. Use a raw `Message` flow if your application needs to inspect WebSocket close status codes directly.

### WebSocket close messages from typed transformers and application failures are more consistent

Play now preserves more application-level WebSocket close reasons as WebSocket Close frames instead of turning them into generic stream termination.

For Scala WebSockets, if an application source failed with `play.api.http.websocket.WebSocketCloseException`, the close status carried by the exception was not preserved reliably. This could be handled as a generic application stream failure instead of closing the WebSocket with the supplied status. Play now closes the connection with the exception's embedded `CloseMessage`.

Scala
: @[websocket-close-exception](code/WebSocketCloseMigration.scala)

Scala high-level JSON WebSockets created with `WebSocket.MessageFlowTransformer.jsonMessageFlowTransformer[In, Out]` now close with status `1003` when incoming JSON is syntactically valid but fails the configured `Reads[In]` validation. The invalid message is still not delivered to the typed application flow, but the remote peer now receives the intended `1003` close status with the validation error reason.

Scala
: @[typed-json-validation](code/WebSocketCloseMigration.scala)

Java typed JSON WebSockets created with `play.mvc.WebSocket.json(Class)` now use a bounded generic close reason for JSON decoding failures. The close status remains `1003`, but the reason is now `"Unable to parse JSON message"` instead of the underlying Jackson exception message.

Java
: @[typed-json-decoding](code/WebSocketCloseMigration.java)

This avoids creating invalid WebSocket Close frames: [RFC 6455 section 5.5](https://datatracker.ietf.org/doc/html/rfc6455#section-5.5) limits all control frame payloads, including Close frames, to 125 bytes, and [section 5.5.1](https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1) defines the first two bytes of a Close frame body as the status code, leaving at most 123 bytes for the UTF-8 reason.

### WebSocket Close frame handling is more RFC-compliant

Play now normalizes additional WebSocket Close frame edge cases in the common WebSocket flow handler. These changes keep application-visible close status reporting compatible where possible, while avoiding invalid Close frames on the wire.

[RFC 6455 section 5.5](https://datatracker.ietf.org/doc/html/rfc6455#section-5.5) limits WebSocket control frames to 125 bytes. [Section 5.5.1](https://datatracker.ietf.org/doc/html/rfc6455#section-5.5.1) defines Close frame payloads as an optional 2-byte status code followed by an optional UTF-8 reason. [Section 7.4.1](https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1) defines status codes such as `1005`, `1006`, and `1015` as reserved values that must not be sent as status codes in a Close control frame.

| **Case** | **Previous behavior** | **New behavior** |
| --- | --- | --- |
| Play echoes an empty Close frame from the remote peer | Play could represent the echoed frame as `CloseMessage(Some(1005), "")`. | Play sends an empty Close frame, represented as `CloseMessage(None, "")`, so `1005` is not sent on the wire. |
| Application code sends `CloseMessage(Some(1005), "")` | Play could pass `1005` toward the backend as a status code. | Play sends an empty Close frame, represented as `CloseMessage(None, "")`. |
| Application code sends a reserved or invalid close status code, such as `1006` or `999` | Play sent the status code unchanged. | Play still sends the status code unchanged for compatibility, but logs a warning because the value is not valid in a Close frame. |
| Application code sends a Close reason that cannot be encoded as UTF-8 | Play could pass the reason toward the backend unchanged. | Play logs a warning and drops the reason. |
| Application code sends a Close reason longer than 123 UTF-8 bytes | Play could pass an invalid oversized Close frame toward the backend. | Play logs a warning and truncates the reason to the longest valid UTF-8 prefix that fits in 123 bytes. |
| Application code sends `CloseMessage(None, "reason")` | The close reason had no valid wire encoding because Close reasons require a status code. | Play logs a warning and sends `CloseMessage(None, "")`, dropping the reason. |

Applications that create raw `CloseMessage` values should avoid sending reserved status codes such as `1005`, `1006`, and `1015`, and should keep Close reasons short enough to fit in 123 UTF-8 bytes.
