package io.github.mobdev.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobdev.R
import io.github.mobdev.data.cache.ChatCacheStore
import io.github.mobdev.data.mapper.messageIdAsLong
import io.github.mobdev.data.network.NetworkMonitor
import io.github.mobdev.data.repository.ChatRepository
import io.github.mobdev.data.session.CredentialsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val credentialsStore: CredentialsStore,
    private val cacheStore: ChatCacheStore,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        observeNetwork()

        viewModelScope.launch {
            val saved = credentialsStore.savedCredentials.first()
            if (saved != null) {
                _state.update {
                    it.copy(
                        username = saved.username,
                        password = saved.password,
                    )
                }
                loginInternal(saved.username, saved.password, saveCredentials = false)
            }
        }

        viewModelScope.launch {
            repository.unauthorized.collect {
                credentialsStore.clear()
                _state.value = ChatUiState(error = UiText(R.string.error_session_expired))
            }
        }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun onMessageTextChange(value: String) {
        _state.update { it.copy(messageText = value, error = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun login() {
        val username = _state.value.username.trim()
        val password = _state.value.password

        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = UiText(R.string.error_empty_credentials)) }
            return
        }

        viewModelScope.launch {
            loginInternal(username, password, saveCredentials = true)
        }
    }

    private suspend fun loginInternal(
        username: String,
        password: String,
        saveCredentials: Boolean,
    ) {
        _state.update { it.copy(isLoading = true, error = null) }

        repository.login(username, password)
            .onSuccess {
                if (saveCredentials) {
                    credentialsStore.save(username, password)
                }
                _state.update {
                    it.copy(
                        username = username,
                        password = password,
                        isLoggedIn = true,
                        isLoading = false,
                        error = null,
                    )
                }
                loadChannels()
            }
            .onFailure { throwable ->
                if (throwable is ChatRepository.HttpException && throwable.code == HTTP_UNAUTHORIZED) {
                    credentialsStore.clear()
                    _state.update {
                        it.copy(
                            isLoggedIn = false,
                            isLoading = false,
                            error = throwable.toReadableMessage(isLogin = true),
                        )
                    }
                    return@onFailure
                }

                val cachedChannels = cacheStore.loadChannels()
                if (cachedChannels.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            username = username,
                            password = password,
                            isLoggedIn = true,
                            isLoading = false,
                            channels = cachedChannels,
                            error = UiText(R.string.offline_cached_data),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isLoggedIn = false,
                            isLoading = false,
                            error = throwable.toReadableMessage(isLogin = true),
                        )
                    }
                }
            }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            credentialsStore.clear()
            _state.value = ChatUiState()
        }
    }

    fun loadChannels() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            repository.fetchChannels()
                .onSuccess { channels ->
                    cacheStore.saveChannels(channels)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = channels,
                        )
                    }
                    sendPendingMessages()
                }
                .onFailure { throwable ->
                    val cachedChannels = cacheStore.loadChannels()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = if (cachedChannels.isNotEmpty()) cachedChannels else it.channels,
                            error = if (cachedChannels.isNotEmpty()) {
                                UiText(R.string.offline_cached_data)
                            } else {
                                throwable.toReadableMessage()
                            },
                        )
                    }
                }
        }
    }

    fun openChannel(channel: String) {
        if (_state.value.selectedChannel == channel) return

        _state.update {
            it.copy(
                selectedChannel = channel,
                messages = emptyList(),
                hasMoreMessages = true,
                messageText = "",
                error = null,
            )
        }
        loadMessages(channel)
    }

    fun closeChannel() {
        _state.update {
            it.copy(
                selectedChannel = null,
                messages = emptyList(),
                hasMoreMessages = true,
                messageText = "",
                openedImagePath = null,
                error = null,
            )
        }
    }

    fun loadMessages(channel: String? = null) {
        val targetChannel = channel ?: _state.value.selectedChannel ?: return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            repository.fetchMessages(channel = targetChannel)
                .onSuccess { messages ->
                    cacheStore.saveMessages(targetChannel, messages)
                    val pending = cacheStore.loadPendingMessages(targetChannel).map { it.toChatMessage() }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            messages = mergeMessages(messages, pending),
                            hasMoreMessages = messages.size == ChatRepository.PAGE_SIZE,
                        )
                    }
                    sendPendingMessages()
                }
                .onFailure { throwable ->
                    val cachedMessages = cacheStore.loadMessages(targetChannel)
                    val pending = cacheStore.loadPendingMessages(targetChannel).map { it.toChatMessage() }
                    val hasCache = cachedMessages.isNotEmpty() || pending.isNotEmpty()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            messages = if (hasCache) mergeMessages(cachedMessages, pending) else it.messages,
                            error = if (hasCache) {
                                UiText(R.string.offline_cached_data)
                            } else {
                                throwable.toReadableMessage()
                            },
                        )
                    }
                }
        }
    }

    fun loadOlderMessages() {
        val current = _state.value
        val channel = current.selectedChannel ?: return
        val firstMessageId = current.messages.minOfOrNull { messageIdAsLong(it.id) } ?: return
        if (current.isLoadingMore || !current.hasMoreMessages) return

        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true, error = null) }

            repository.fetchMessages(
                channel = channel,
                lastKnownId = firstMessageId,
                reverse = true,
            )
                .onSuccess { olderMessages ->
                    cacheStore.saveMessages(channel, olderMessages)
                    _state.update { oldState ->
                        val merged = mergeMessages(olderMessages, oldState.messages)

                        oldState.copy(
                            isLoadingMore = false,
                            messages = merged,
                            hasMoreMessages = olderMessages.size == ChatRepository.PAGE_SIZE,
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isLoadingMore = false,
                            error = throwable.toReadableMessage(),
                        )
                    }
                }
        }
    }

    fun sendMessage() {
        val current = _state.value
        val channel = current.selectedChannel ?: return
        val text = current.messageText.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            if (!current.isOnline) {
                val pending = cacheStore.addPendingMessage(
                    from = current.username.trim(),
                    channel = channel,
                    text = text,
                )
                _state.update {
                    it.copy(
                        messageText = "",
                        messages = mergeMessages(it.messages, listOf(pending.toChatMessage())),
                        error = UiText(R.string.message_saved_offline),
                    )
                }
                return@launch
            }

            _state.update { it.copy(isLoading = true, error = null) }

            repository.sendTextMessage(
                username = current.username.trim(),
                channel = channel,
                text = text,
            )
                .onSuccess {
                    _state.update { it.copy(messageText = "", isLoading = false) }
                    loadMessages(channel)
                }
                .onFailure { throwable ->
                    val pending = cacheStore.addPendingMessage(
                        from = current.username.trim(),
                        channel = channel,
                        text = text,
                    )
                    _state.update {
                        it.copy(
                            messageText = "",
                            isLoading = false,
                            messages = mergeMessages(it.messages, listOf(pending.toChatMessage())),
                            error = UiText(R.string.message_saved_offline),
                        )
                    }
                }
        }
    }


    private fun observeNetwork() {
        viewModelScope.launch {
            networkMonitor.isOnline
                .distinctUntilChanged()
                .collect { isOnline ->
                    val wasOffline = !_state.value.isOnline
                    _state.update { it.copy(isOnline = isOnline) }
                    if (isOnline && wasOffline && _state.value.isLoggedIn) {
                        sendPendingMessages()
                        loadChannels()
                        _state.value.selectedChannel?.let { loadMessages(it) }
                    }
                }
        }
    }

    private suspend fun sendPendingMessages() {
        if (!_state.value.isOnline) return

        val pendingMessages = cacheStore.loadPendingMessages()
        pendingMessages.forEach { pending ->
            repository.sendTextMessage(
                username = pending.from,
                channel = pending.channel,
                text = pending.text,
            ).onSuccess {
                cacheStore.removePendingMessage(pending.localId)
            }
        }
    }

    private fun mergeMessages(
        first: List<io.github.mobdev.data.domain.ChatMessage>,
        second: List<io.github.mobdev.data.domain.ChatMessage>,
    ): List<io.github.mobdev.data.domain.ChatMessage> {
        return (first + second)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<io.github.mobdev.data.domain.ChatMessage> { it.id.startsWith(PENDING_PREFIX) }
                    .thenBy { messageIdAsLong(it.id) }
                    .thenBy { it.time ?: 0L },
            )
    }

    fun openImage(path: String) {
        _state.update { it.copy(openedImagePath = path) }
    }

    fun closeImage() {
        _state.update { it.copy(openedImagePath = null) }
    }

    fun imageUrl(path: String, fullResolution: Boolean = false): String {
        return repository.imageUrl(path, fullResolution)
    }

    fun isMyMessage(from: String): Boolean {
        return from == _state.value.username.trim()
    }

    private fun Throwable.toReadableMessage(isLogin: Boolean = false): UiText {
        return when (this) {
            is ChatRepository.HttpException -> when {
                isLogin && code == HTTP_UNAUTHORIZED -> UiText(R.string.error_invalid_credentials)
                code == HTTP_UNAUTHORIZED -> UiText(R.string.error_session_expired)
                else -> UiText(R.string.error_http, listOf(code))
            }

            else -> UiText(R.string.error_unknown)
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val credentialsStore: CredentialsStore,
        private val cacheStore: ChatCacheStore,
        private val networkMonitor: NetworkMonitor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository, credentialsStore, cacheStore, networkMonitor) as T
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val PENDING_PREFIX = "pending_"
    }
}
