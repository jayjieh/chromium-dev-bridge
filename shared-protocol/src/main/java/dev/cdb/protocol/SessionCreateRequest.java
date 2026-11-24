package dev.cdb.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionCreateRequest(
        String projectId,
        String label,
        Boolean headless,
        Viewport viewport,
        ViewportPreset viewportPreset,
        String locale,
        Double deviceScaleFactor,
        String userAgent,
        SessionIsolation isolation,
        String profileName,
        String channel,
        String connectEndpoint,
        Integer idleTimeoutSeconds,
        Boolean agentAccess,
        String initialUrl) {

    public static SessionCreateRequest defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String projectId, label, locale, userAgent, profileName, channel, connectEndpoint, initialUrl;
        private Boolean headless, agentAccess;
        private Viewport viewport;
        private ViewportPreset viewportPreset;
        private Double deviceScaleFactor;
        private SessionIsolation isolation;
        private Integer idleTimeoutSeconds;

        public Builder projectId(String v) { projectId = v; return this; }
        public Builder label(String v) { label = v; return this; }
        public Builder headless(Boolean v) { headless = v; return this; }
        public Builder viewport(Viewport v) { viewport = v; return this; }
        public Builder viewportPreset(ViewportPreset v) { viewportPreset = v; return this; }
        public Builder locale(String v) { locale = v; return this; }
        public Builder deviceScaleFactor(Double v) { deviceScaleFactor = v; return this; }
        public Builder userAgent(String v) { userAgent = v; return this; }
        public Builder isolation(SessionIsolation v) { isolation = v; return this; }
        public Builder profileName(String v) { profileName = v; return this; }
        public Builder channel(String v) { channel = v; return this; }
        public Builder connectEndpoint(String v) { connectEndpoint = v; return this; }
        public Builder idleTimeoutSeconds(Integer v) { idleTimeoutSeconds = v; return this; }
        public Builder agentAccess(Boolean v) { agentAccess = v; return this; }
        public Builder initialUrl(String v) { initialUrl = v; return this; }

        public SessionCreateRequest build() {
            return new SessionCreateRequest(projectId, label, headless, viewport, viewportPreset, locale, deviceScaleFactor,
                    userAgent, isolation, profileName, channel, connectEndpoint, idleTimeoutSeconds, agentAccess, initialUrl);
        }
    }
}
