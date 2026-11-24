package dev.cdb.intellij

import com.fasterxml.jackson.databind.JsonNode
import dev.cdb.protocol.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Suggests the local URL of a project's development server from well-known configuration files
 * (package.json scripts, Vite/Angular/Next conventions, Spring Boot server.port).
 */
object DevServerDetector {
    data class Suggestion(val url: String, val reason: String)

    fun detect(projectDir: Path?): List<Suggestion> {
        if (projectDir == null || !Files.isDirectory(projectDir)) return emptyList()
        val out = mutableListOf<Suggestion>()
        val pkg = projectDir.resolve("package.json")
        if (Files.isRegularFile(pkg)) out += fromPackageJson(runCatching { Json.mapper().readTree(pkg.toFile()) }.getOrNull())
        for (name in listOf("src/main/resources/application.properties", "src/main/resources/application.yml")) {
            val f = projectDir.resolve(name)
            if (Files.isRegularFile(f)) {
                val text = Files.readString(f)
                val port = Regex("""server\.port\s*[=:]\s*(\d+)""").find(text)?.groupValues?.get(1)
                    ?: Regex("""(?m)^server:\s*\n\s+port:\s*(\d+)""").find(text)?.groupValues?.get(1)
                out += Suggestion("http://localhost:${port ?: "8080"}", "Spring Boot ($name)")
            }
        }
        return out.distinctBy { it.url }
    }

    internal fun fromPackageJson(json: JsonNode?): List<Suggestion> {
        if (json == null) return emptyList()
        val scripts = json.path("scripts")
        val deps = json.path("dependencies").fieldNames().asSequence().toSet() + json.path("devDependencies").fieldNames().asSequence().toSet()
        val dev = scripts.path("dev").asText("") + " " + scripts.path("start").asText("")
        val explicit = Regex("""(?:--port|-p)[ =](\d{2,5})""").find(dev)?.groupValues?.get(1)
        val out = mutableListOf<Suggestion>()
        if (explicit != null) out += Suggestion("http://localhost:$explicit", "port in package.json scripts")
        when {
            "vite" in deps || dev.contains("vite") -> out += Suggestion("http://localhost:5173", "Vite default port")
            "next" in deps -> out += Suggestion("http://localhost:3000", "Next.js default port")
            "@angular/core" in deps -> out += Suggestion("http://localhost:4200", "Angular CLI default port")
            "react-scripts" in deps -> out += Suggestion("http://localhost:3000", "Create React App default port")
            "nuxt" in deps -> out += Suggestion("http://localhost:3000", "Nuxt default port")
            "@sveltejs/kit" in deps -> out += Suggestion("http://localhost:5173", "SvelteKit default port")
        }
        return out
    }
}
