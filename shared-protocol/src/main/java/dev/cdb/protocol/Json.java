package dev.cdb.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared Jackson configuration for clients that are not Spring-managed. */
public final class Json {
    private static final ObjectMapper MAPPER = configure(new ObjectMapper());

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectMapper configure(ObjectMapper m) {
        return m.registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private Json() {
    }
}
