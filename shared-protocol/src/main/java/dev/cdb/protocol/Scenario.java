package dev.cdb.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Scenario(String name, String baseUrl, Map<String, String> variables,
                       Map<String, Map<String, String>> fixtures, String fixture, Integer defaultTimeoutMs,
                       Boolean screenshotOnFailure, List<ScenarioStep> steps) {
}
