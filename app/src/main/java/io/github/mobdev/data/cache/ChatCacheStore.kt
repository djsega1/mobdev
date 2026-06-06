package io.github.mobdev.data.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import io.github.mobdev.data.domain.ChatMessage
import io.github.mobdev.data.domain.MessageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChatCacheStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun saveChannels(channels: List<String>) = withContext(Dispatchers.IO) {
        val json = JSONArray()
        channels.forEach { json.put(it) }
        prefs.edit().putString(KEY_CHANNELS, json.toString()).apply()
    }

    suspend fun loadChannels(): List<String> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return@withContext emptyList()
        runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    add(json.getString(index))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveMessages(channel: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        val merged = (loadMessagesSync(channel) + messages)
            .distinctBy { it.id }
            .sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }

        prefs.edit()
            .putString(messagesKey(channel), messagesToJson(merged).toString())
            .apply()
    }

    suspend fun loadMessages(channel: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        loadMessagesSync(channel)
    }

    suspend fun addPendingMessage(
        from: String,
        channel: String,
        text: String,
    ): PendingMessage = withContext(Dispatchers.IO) {
        val pending = PendingMessage(
            localId = UUID.randomUUID().toString(),
            from = from,
            channel = channel,
            text = text,
            createdAt = System.currentTimeMillis(),
        )
        val updated = loadPendingMessagesSync() + pending
        savePendingMessagesSync(updated)
        pending
    }

    suspend fun loadPendingMessages(channel: String? = null): List<PendingMessage> = withContext(Dispatchers.IO) {
        loadPendingMessagesSync().filter { channel == null || it.channel == channel }
    }

    suspend fun removePendingMessage(localId: String) = withContext(Dispatchers.IO) {
        val updated = loadPendingMessagesSync().filterNot { it.localId == localId }
        savePendingMessagesSync(updated)
    }

    private fun loadMessagesSync(channel: String): List<ChatMessage> {
        val raw = prefs.getString(messagesKey(channel), null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    val content = item.getJSONObject(FIELD_CONTENT)
                    val messageContent = when (content.getString(FIELD_TYPE)) {
                        TYPE_TEXT -> MessageContent.Text(content.getString(FIELD_TEXT))
                        TYPE_IMAGE -> MessageContent.Image(content.getString(FIELD_LINK))
                        else -> MessageContent.Unknown
                    }

                    add(
                        ChatMessage(
                            id = item.getString(FIELD_ID),
                            from = item.getString(FIELD_FROM),
                            to = item.optString(FIELD_TO).takeIf { it.isNotBlank() },
                            content = messageContent,
                            time = item.optLong(FIELD_TIME).takeIf { item.has(FIELD_TIME) },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun messagesToJson(messages: List<ChatMessage>): JSONArray {
        val json = JSONArray()
        messages.forEach { message ->
            val contentJson = JSONObject()
            when (val content = message.content) {
                is MessageContent.Text -> {
                    contentJson.put(FIELD_TYPE, TYPE_TEXT)
                    contentJson.put(FIELD_TEXT, content.text)
                }

                is MessageContent.Image -> {
                    contentJson.put(FIELD_TYPE, TYPE_IMAGE)
                    contentJson.put(FIELD_LINK, content.link)
                }

                MessageContent.Unknown -> {
                    contentJson.put(FIELD_TYPE, TYPE_UNKNOWN)
                }
            }

            json.put(
                JSONObject()
                    .put(FIELD_ID, message.id)
                    .put(FIELD_FROM, message.from)
                    .put(FIELD_TO, message.to)
                    .put(FIELD_CONTENT, contentJson)
                    .apply {
                        message.time?.let { put(FIELD_TIME, it) }
                    },
            )
        }
        return json
    }

    private fun loadPendingMessagesSync(): List<PendingMessage> {
        val raw = prefs.getString(KEY_PENDING_MESSAGES, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        PendingMessage(
                            localId = item.getString(FIELD_LOCAL_ID),
                            from = item.getString(FIELD_FROM),
                            channel = item.getString(FIELD_CHANNEL),
                            text = item.getString(FIELD_TEXT),
                            createdAt = item.getLong(FIELD_CREATED_AT),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun savePendingMessagesSync(messages: List<PendingMessage>) {
        val json = JSONArray()
        messages.forEach { message ->
            json.put(
                JSONObject()
                    .put(FIELD_LOCAL_ID, message.localId)
                    .put(FIELD_FROM, message.from)
                    .put(FIELD_CHANNEL, message.channel)
                    .put(FIELD_TEXT, message.text)
                    .put(FIELD_CREATED_AT, message.createdAt),
            )
        }
        prefs.edit().putString(KEY_PENDING_MESSAGES, json.toString()).apply()
    }

    private fun messagesKey(channel: String): String {
        val encoded = Base64.encodeToString(channel.toByteArray(), Base64.NO_WRAP)
        return "messages_$encoded"
    }

    private companion object {
        const val PREFS_NAME = "chat_cache"
        const val KEY_CHANNELS = "channels"
        const val KEY_PENDING_MESSAGES = "pending_messages"

        const val FIELD_ID = "id"
        const val FIELD_LOCAL_ID = "localId"
        const val FIELD_FROM = "from"
        const val FIELD_TO = "to"
        const val FIELD_CHANNEL = "channel"
        const val FIELD_CONTENT = "content"
        const val FIELD_TYPE = "type"
        const val FIELD_TEXT = "text"
        const val FIELD_LINK = "link"
        const val FIELD_TIME = "time"
        const val FIELD_CREATED_AT = "createdAt"

        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_UNKNOWN = "unknown"
    }
}

data class PendingMessage(
    val localId: String,
    val from: String,
    val channel: String,
    val text: String,
    val createdAt: Long,
) {
    fun toChatMessage(): ChatMessage {
        return ChatMessage(
            id = "pending_$localId",
            from = from,
            to = channel,
            content = MessageContent.Text(text),
            time = createdAt,
        )
    }
}
