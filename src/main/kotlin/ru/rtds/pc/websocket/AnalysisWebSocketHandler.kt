package ru.rtds.pc.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

@Component
class AnalysisWebSocketHandler(
    private val objectMapper: ObjectMapper,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val subs = ConcurrentHashMap<String, WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val id = sessionIdFrom(session) ?: run {
            session.close(CloseStatus.BAD_DATA); return
        }
        subs[id] = session
        log.info("WS connected for session {}", id)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val id = sessionIdFrom(session) ?: return
        subs.remove(id)
        log.info("WS disconnected for session {}", id)
    }

    fun send(sessionId: String, payload: Any) {
        val ws = subs[sessionId] ?: return
        if (!ws.isOpen) return
        try {
            val json = objectMapper.writeValueAsString(payload)
            synchronized(ws) { ws.sendMessage(TextMessage(json)) }
        } catch (e: Exception) {
            log.warn("WS send failed for {}: {}", sessionId, e.message)
        }
    }

    fun close(sessionId: String) {
        subs[sessionId]?.let {
            runCatching { it.close(CloseStatus.NORMAL) }
            subs.remove(sessionId)
        }
    }

    private fun sessionIdFrom(s: WebSocketSession): String? =
        s.uri?.path?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
}
