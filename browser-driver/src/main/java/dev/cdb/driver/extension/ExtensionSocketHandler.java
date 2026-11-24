package dev.cdb.driver.extension;

import dev.cdb.provider.extension.ExtensionHub;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** WebSocket endpoint for the Chrome extension; authentication happens in SecurityFilter before the upgrade. */
@Component
public class ExtensionSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ExtensionSocketHandler.class);
    private final ExtensionHub hub;
    private final Map<String, Transport> transports = new ConcurrentHashMap<>();

    public ExtensionSocketHandler(ExtensionHub hub) {
        this.hub = hub;
    }

    private record Transport(WebSocketSession ws) implements ExtensionHub.Transport {
        public String id() {
            return "ext-" + ws.getId();
        }

        public void send(String text) {
            try {
                ws.sendMessage(new TextMessage(text));
            } catch (IOException | IllegalStateException e) {
                throw new IllegalStateException("Extension socket closed", e);
            }
        }

        public void close() {
            try {
                ws.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Transport t = new Transport(new ConcurrentWebSocketSessionDecorator(session, 30_000, 64 * 1024 * 1024));
        transports.put(session.getId(), t);
        hub.connected(t);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Transport t = transports.get(session.getId());
        if (t != null) hub.received(t, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Transport t = transports.remove(session.getId());
        if (t != null) hub.disconnected(t);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Extension socket error: {}", exception.toString());
    }
}
