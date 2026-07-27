package com.haloscreen.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response

/**
 * Connects to the PC companion server (mock or real) over WebSocket,
 * decodes each JSON message into a GameState, and republishes it as a
 * StateFlow the Compose UI can collect.
 *
 * Auto-reconnects with a short backoff if the connection drops - useful
 * since this is a LAN app and Wi-Fi hiccups are common.
 */
class GameStateViewModel : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()
    private var socket: WebSocket? = null

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status

    private var shouldReconnect = false
    private var currentUrl: String = ""

    fun connect(url: String) {
        currentUrl = url
        shouldReconnect = true
        openSocket(url)
    }

    fun disconnect() {
        shouldReconnect = false
        socket?.close(1000, "User disconnected")
        socket = null
        _status.value = ConnectionStatus.Disconnected
    }

    private fun openSocket(url: String) {
        _status.value = ConnectionStatus.Connecting
        val request = Request.Builder().url(url).build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = ConnectionStatus.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString<GameState>(text) }
                    .onSuccess { _gameState.value = it }
                // Silently ignore malformed frames rather than crashing the HUD;
                // consider logging this during development.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = ConnectionStatus.Error(t.message ?: "Unknown connection error")
                maybeReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_status.value != ConnectionStatus.Disconnected) {
                    _status.value = ConnectionStatus.Disconnected
                    maybeReconnect()
                }
            }
        })
    }

    private fun maybeReconnect() {
        if (!shouldReconnect) return
        viewModelScope.launch {
            delay(2000)
            if (shouldReconnect) openSocket(currentUrl)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        client.dispatcher.executorService.shutdown()
    }
}
