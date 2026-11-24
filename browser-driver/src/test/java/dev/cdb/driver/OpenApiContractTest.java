package dev.cdb.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** ADR-0004: every documented operation is implemented and every implemented operation is documented. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "cdb.data-dir=target/test-data/contract")
@Import(FakeProviderConfig.class)
class OpenApiContractTest {
    private static final Set<String> HTTP = Set.of("get", "post", "put", "delete", "patch");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    @SuppressWarnings("unchecked")
    void controllersMatchOpenApiDocument() throws Exception {
        Set<String> documented = new TreeSet<>();
        try (InputStream in = new ClassPathResource("static/api/OPENAPI.yaml").getInputStream()) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
            paths.forEach((path, ops) -> ops.keySet().stream().filter(HTTP::contains)
                    .forEach(m -> documented.add(m.toUpperCase() + " " + path)));
        }
        documented.remove("GET /api/v1/events"); // WebSocket upgrade, not an MVC handler

        Set<String> implemented = new TreeSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1")) continue;
                info.getMethodsCondition().getMethods().forEach(m -> implemented.add(m.name() + " " + path));
            }
        }
        assertThat(implemented).isEqualTo(documented);
    }
}
