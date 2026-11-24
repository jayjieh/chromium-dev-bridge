package dev.cdb.driver.ws;

import dev.cdb.core.Principal;
import dev.cdb.driver.security.SecurityFilter;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Copies the principal authenticated by SecurityFilter into the WebSocket session attributes. */
class PrincipalHandshakeInterceptor implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest s
                && s.getServletRequest().getAttribute(SecurityFilter.PRINCIPAL_ATTRIBUTE) instanceof Principal p) {
            attributes.put(SecurityFilter.PRINCIPAL_ATTRIBUTE, p);
            return true;
        }
        response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception ex) {
    }
}
