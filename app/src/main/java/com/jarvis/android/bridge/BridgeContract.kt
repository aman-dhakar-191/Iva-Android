package com.jarvis.android.bridge

import org.json.JSONObject

/**
 * The wire format shared by both directions of the native <-> web channel.
 *
 * Every frame is a JSON object of the shape:
 *
 *     { "v": 1, "id": "<optional correlation id>", "type": "<name>", "payload": { ... } }
 *
 * `v` is the contract version, bumped only for breaking changes, so both sides
 * can refuse frames they do not understand rather than mis-parsing them. `id`
 * is echoed back on the matching `ack`/`pong` so the web side can await a reply.
 *
 * The full catalogue of `type` values lives in BRIDGE.md at the repository root;
 * keep that file and [Inbound]/[Outbound] below in step.
 */
object BridgeContract {
    const val VERSION = 1

    /** Names the web page may send to the native side. */
    object Inbound {
        const val PING = "ping"
        const val NOTIFY = "notify"
        const val TTS_SPEAK = "tts.speak"
        const val TTS_STOP = "tts.stop"
        const val SHARE = "share"
        const val HAPTIC = "haptic"
        const val SESSION_START = "session.start"
        const val SESSION_STOP = "session.stop"
        const val SESSION_QUERY = "session.query"
        const val OPEN_EXTERNAL = "open.external"
    }

    /** Names the native side may send to the web page. */
    object Outbound {
        const val READY = "ready"
        const val ACK = "ack"
        const val PONG = "pong"
        const val SESSION_STATE = "session.state"
        const val TTS_STATE = "tts.state"
        const val CHAT_INJECT = "chat.inject"
        const val LIFECYCLE = "lifecycle"
    }
}

/** A parsed inbound frame. */
data class BridgeMessage(
    val version: Int,
    val id: String?,
    val type: String,
    val payload: JSONObject
) {
    companion object {
        fun parse(raw: String): BridgeMessage? = try {
            val json = JSONObject(raw)
            val type = json.optString("type").takeIf { it.isNotBlank() }
            if (type == null) null else BridgeMessage(
                version = json.optInt("v", BridgeContract.VERSION),
                id = json.optString("id").takeIf { it.isNotBlank() },
                type = type,
                payload = json.optJSONObject("payload") ?: JSONObject()
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Builds an outbound frame. */
fun bridgeFrame(type: String, id: String? = null, payload: JSONObject = JSONObject()): JSONObject =
    JSONObject().apply {
        put("v", BridgeContract.VERSION)
        if (id != null) put("id", id)
        put("type", type)
        put("payload", payload)
    }
