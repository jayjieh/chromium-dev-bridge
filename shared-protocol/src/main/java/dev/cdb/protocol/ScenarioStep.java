package dev.cdb.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioStep(String action, String description, String path, String url, Locator locator, String text,
                           String value, List<String> values, String key, String name, String urlPattern, Integer status,
                           String baseline, Double maxDiffRatio, Integer timeoutMs) {
}
