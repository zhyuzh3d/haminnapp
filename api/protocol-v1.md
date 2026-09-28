# Haminn RPC protocol v1

The Native host registers `__haminnTransportV1` before the first document script, restricted to the instance's exact origin and main frame. A document sends `hello`; Native returns a random challenge; the same reply proxy must return `ack`. Requests are accepted only after both the challenge and top-level navigation commit.

Every request carries `documentId`, Native-issued `sessionId`, and `documentEpoch`. Navigation invalidates all three bindings and cancels the session scope. Web input never chooses `appId`, profile, data generation, release, or role. `host.*` is accepted only from the fixed Store origin.

Responses use `{kind:"response", id, documentEpoch, ok, result}` or an error object `{code,message,retryable}`. Events use `{kind:"event", event, documentEpoch, data}`. Messages larger than 256 KiB and calls not completed within 60 seconds are rejected. A message over the cap is answered with `E_QUOTA` on its own request id and the measured size, so the caller can react instead of waiting for its client timeout. Large binary data moves through logical file IDs, not RPC JSON: a page that holds more bytes than one message can carry writes them with `files.beginWrite`/`appendBytes`/`finishWrite` and passes the resulting id to `network.request({bodyLogicalFileId})` or to a `multipart` part.

Stable error codes are `E_INVALID_ARGUMENT`, `E_UNSUPPORTED`, `E_ORIGIN_DENIED`, `E_CAPABILITY_DENIED`, `E_OS_PERMISSION_DENIED`, `E_SESSION_EXPIRED`, `E_CANCELLED`, `E_TIMEOUT`, `E_CONFLICT`, `E_QUOTA`, `E_STORAGE`, `E_NETWORK`, and `E_INTERNAL`.

`system.language` is a permission-free system-fact call. It returns the current Android system locale as a primary BCP-47 `languageTag`, split `language`/`script`/`region` fields, and the ordered `preferredLanguages` list. It does not read or change a happ's own language preference.

`app.backup` is the one backup entry a page may call for itself. The instance comes from the session and the method takes no arguments, so a happ can never name another instance. The user picks the destination through the system file picker before anything is written; the archive then uses the same format, content set and limits as the Store's single-app export. A dismissed picker returns `{cancelled:true}` and writes nothing.
