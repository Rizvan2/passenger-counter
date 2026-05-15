package ru.rtds.pc.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import ru.rtds.pc.websocket.AnalysisWebSocketHandler

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: AnalysisWebSocketHandler,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws/analysis/*").setAllowedOriginPatterns("*")
    }
}
