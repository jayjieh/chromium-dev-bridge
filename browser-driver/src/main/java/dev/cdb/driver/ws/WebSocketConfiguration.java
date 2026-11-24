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
    private final CdbProperties props;

    public WebSocketConfiguration(EventStreamHandler handler, CdbProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Origin is enforced by SecurityFilter (requests with an Origin header are rejected unless allowed).
        registry.addHandler(handler, "/api/v1/events").addInterceptors(new PrincipalHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean f = new ServletServerContainerFactoryBean();
        f.setMaxTextMessageBufferSize(props.security().maxWebsocketMessageBytes());
        f.setMaxBinaryMessageBufferSize(props.security().maxWebsocketMessageBytes());
        f.setMaxSessionIdleTimeout(30L * 60 * 1000);
        return f;
    }
}
