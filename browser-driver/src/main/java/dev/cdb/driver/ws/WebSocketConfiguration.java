package dev.cdb.driver.ws;

import dev.cdb.driver.CdbProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final EventStreamHandler handler;
    private final dev.cdb.driver.extension.ExtensionSocketHandler extensionHandler;
    private final CdbProperties props;

    public WebSocketConfiguration(EventStreamHandler handler, dev.cdb.driver.extension.ExtensionSocketHandler extensionHandler,
                                  CdbProperties props) {
        this.handler = handler;
        this.extensionHandler = extensionHandler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Origin is enforced by SecurityFilter (requests with an Origin header are rejected unless allowed).
        registry.addHandler(handler, "/api/v1/events").addInterceptors(new PrincipalHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
        // Chrome extension: origin and pairing token are checked by SecurityFilter; the server echoes the sub-protocol.
        org.springframework.web.socket.server.support.DefaultHandshakeHandler handshake =
                new org.springframework.web.socket.server.support.DefaultHandshakeHandler();
        handshake.setSupportedProtocols(dev.cdb.driver.security.SecurityFilter.EXTENSION_PROTOCOL);
        registry.addHandler(extensionHandler, dev.cdb.driver.security.SecurityFilter.EXTENSION_PATH)
                .setHandshakeHandler(handshake).setAllowedOriginPatterns("chrome-extension://*");
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean f = new ServletServerContainerFactoryBean();
        // Large enough for screenshots relayed by the Chrome extension; /api/v1/events enforces its own smaller limit.
        f.setMaxTextMessageBufferSize(32 * 1024 * 1024);
        f.setMaxBinaryMessageBufferSize(props.security().maxWebsocketMessageBytes());
        f.setMaxSessionIdleTimeout(30L * 60 * 1000);
        return f;
    }
}
