package io.github.mobdev.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.mobdev.data.domain.MessageContent
import io.github.mobdev.data.repository.ChatRepository
import io.github.mobdev.data.session.CredentialsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val credentialsStore: CredentialsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            credentialsStore.savedCredentials.collect { saved ->
                if (saved != null && !_state.value.isLoggedIn) {
                    _state.update {
                        it.copy(username = saved.username, password = saved.password)
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.unauthorized.collect {
                credentialsStore.clear()
                _state.value = ChatUiState(error = "Сессия истекла. Войдите заново.")
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

    fun login() {
        val username = _state.value.username.trim()
        val password = _state.value.password

        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Введите логин и пароль") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            repository.login(username, password)
                .onSuccess {
                    credentialsStore.save(username, password)
                    _state.update { it.copy(isLoggedIn = true, isLoading = false) }
                    loadChannels()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.toReadableMessage(),
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
                    _state.update {
                        it.copy(
                            isLoading = false,
                            channels = channels,
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.toReadableMessage(),
                        )
                    }
                }
        }
    }

    fun openChannel(channel: String) {
        _state.update {
            it.copy(
                selectedChannel = channel,
                messages = emptyList(),
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
                messageText = "",
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
                    _state.update {
                        it.copy(
                            isLoading = false,
                            messages = messages,
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
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
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.toReadableMessage(),
                        )
                    }
                }
        }
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

    private fun Throwable.toReadableMessage(): String {
        return when (this) {
            is ChatRepository.HttpException -> "Ошибка сервера: HTTP $code"
            else -> message ?: "Неизвестная ошибка"
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val credentialsStore: CredentialsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository, credentialsStore) as T
        }
    }
}
