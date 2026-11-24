package dev.cdb.driver.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.cdb.core.Principal;
import dev.cdb.core.events.EventBus;
import dev.cdb.core.session.SessionManager;
import dev.cdb.driver.metrics.MicrometerDriverMetrics;
import dev.cdb.driver.security.SecurityFilter;
import dev.cdb.protocol.DriverEvent;
import dev.cdb.protocol.EventTypes;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket JSON-RPC 2.0 event stream. Clients call "subscribe"; events are delivered in batches as "events"
 * notifications. Each connection has a bounded queue (oldest events dropped and counted). See WEBSOCKET_PROTOCOL.md.
 */
@Component
public class EventStreamHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(EventStreamHandler.class);
    static final int QUEUE_LIMIT = 2000;
    static final int BATCH_LIMIT = 200;
    static final long FLUSH_MS = 50;

    private final EventBus bus;
    private final SessionManager sessions;
    private final ObjectMapper mapper;
    private final MicrometerDriverMetrics metrics;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cdb-ws-flush");
        t.setDaemon(true);
        return t;
    });
    private AutoCloseable subscription;

    public EventStreamHandler(EventBus bus, SessionManager sessions, ObjectMapper mapper, MicrometerDriverMetrics metrics) {
        this.bus = bus;
        this.sessions = sessions;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    record Subscription(String id, String sessionId, List<String> patterns) {
        boolean matches(DriverEvent e) {
            if (sessionId != null && !sessionId.equals(e.sessionId())) return false;
            if (patterns == null || patterns.isEmpty()) return true;
            for (String p : patterns) if (EventTypes.matches(p, e.type())) return true;
            return false;
        }
    }

    final class Connection {
        final WebSocketSession ws;
        final Principal principal;
        final List<Subscription> subs = new CopyOnWriteArrayList<>();
        final ArrayDeque<DriverEvent> queue = new ArrayDeque<>();
        final Set<String> sessionsSeen = ConcurrentHashMap.newKeySet();
        long dropped;

        Connection(WebSocketSession ws, Principal principal) {
            this.ws = ws;
            this.principal = principal;
        }

        void offer(DriverEvent e) {
            if (subs.stream().noneMatch(s -> s.matches(e))) return;
            if (!principal.isAdmin()) {
                boolean allowed = e.sessionId() != null && (sessions.canAccess(principal, e.sessionId()) || sessionsSeen.contains(e.sessionId()));
                if (!allowed) return;
                sessionsSeen.add(e.sessionId());
            }
            synchronized (queue) {
                if (queue.size() >= QUEUE_LIMIT) {
                    queue.pollFirst();
                    dropped++;
                }
                queue.addLast(e);
            }
        }

        void flush() {
            List<DriverEvent> batch = new ArrayList<>();
            long d;
            synchronized (queue) {
                while (!queue.isEmpty() && batch.size() < BATCH_LIMIT) batch.add(queue.pollFirst());
                d = dropped;
            }
            if (batch.isEmpty()) return;
            ObjectNode msg = mapper.createObjectNode();
            msg.put("jsonrpc", "2.0");
            msg.put("method", "events");
            ObjectNode params = msg.putObject("params");
            params.set("events", mapper.valueToTree(batch));
            params.put("dropped", d);
            send(msg);
        }

        void send(JsonNode msg) {
            try {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
            } catch (IOException | IllegalStateException e) {
                log.debug("WebSocket send failed: {}", e.toString());
            }
        }
    }

    @PostConstruct
    void start() {
        subscription = bus.subscribe(e -> connections.values().forEach(c -> c.offer(e)));
        flusher.scheduleWithFixedDelay(() -> connections.values().forEach(c -> {
            try {
                c.flush();
            } catch (RuntimeException ex) {
                log.debug("Flush failed: {}", ex.toString());
            }
        }), FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() throws Exception {
        if (subscription != null) subscription.close();
        flusher.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Principal p = (Principal) session.getAttributes().get(SecurityFilter.PRINCIPAL_ATTRIBUTE);
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 1024 * 1024);
        connections.put(session.getId(), new Connection(safe, p));
        metrics.webSocketOpened();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (connections.remove(session.getId()) != null) metrics.webSocketClosed();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection c = connections.get(session.getId());
        if (c == null) return;
        JsonNode req;
        try {
            req = mapper.readTree(message.getPayload());
        } catch (IOException e) {
            c.send(error(null, -32700, "Parse error"));
            return;
        }
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        JsonNode params = req.path("params");
        switch (method) {
            case "subscribe" -> {
                String sessionId = params.hasNonNull("sessionId") ? params.get("sessionId").asText() : null;
                if (sessionId != null && !sessions.canAccess(c.principal, sessionId)) {
                    c.send(error(id, -32001, "Session not found: " + sessionId));
                    return;
                }
                if (c.subs.size() >= 50) {
                    c.send(error(id, -32002, "Too many subscriptions"));
                    return;
                }
                List<String> patterns = new ArrayList<>();
                params.path("events").forEach(n -> patterns.add(n.asText()));
                Subscription s = new Subscription("sub-" + AtomicCounter.next(), sessionId, patterns);
                c.subs.add(s);
                if (sessionId != null) c.sessionsSeen.add(sessionId);
                ObjectNode result = mapper.createObjectNode().put("subscriptionId", s.id());
                c.send(result(id, result));
            }
            case "unsubscribe" -> {
                String subId = params.path("subscriptionId").asText();
                boolean removed = c.subs.removeIf(s -> s.id().equals(subId));
                c.send(result(id, mapper.createObjectNode().put("removed", removed)));
            }
            case "ping" -> c.send(result(id, mapper.getNodeFactory().textNode("pong")));
            default -> c.send(error(id, -32601, "Method not found: " + method));
        }
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode n = mapper.createObjectNode().put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        return n;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode n = mapper.createObjectNode().put("jsonrpc", "2.0");
        n.set("id", id);
        n.putObject("error").put("code", code).put("message", message);
        return n;
    }

    int connectionCount() {
        return connections.size();
    }

    private static final class AtomicCounter {
        private static final AtomicInteger N = new AtomicInteger();

        static int next() {
            return N.incrementAndGet();
        }
    }
}
