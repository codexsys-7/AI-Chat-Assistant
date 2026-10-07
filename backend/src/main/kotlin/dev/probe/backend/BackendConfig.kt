package dev.probe.backend

import java.nio.file.Files
import java.nio.file.Path

data class BackendConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val port: Int,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_PORT = 8080

        fun load(
            env: Map<String, String> = System.getenv(),
            dotenv: Map<String, String> = loadDotEnv(),
        ): BackendConfig {
            fun value(key: String): String? {
                return env[key]?.takeIf { it.isNotBlank() } ?: dotenv[key]?.takeIf { it.isNotBlank() }
            }
            return BackendConfig(
                apiKey = value("OPENAI_API_KEY").orEmpty(),
                model = value("OPENAI_MODEL") ?: DEFAULT_MODEL,
                baseUrl = (value("OPENAI_BASE_URL") ?: DEFAULT_BASE_URL).trimEnd('/'),
                port = value("PORT")?.toIntOrNull() ?: DEFAULT_PORT,
            )
        }
    }
}

fun loadDotEnv(paths: List<Path> = defaultDotEnvPaths()): Map<String, String> {
    val file = paths.firstOrNull { path -> Files.isRegularFile(path) } ?: return emptyMap()
    return parseDotEnv(Files.readString(file))
}

fun defaultDotEnvPaths(): List<Path> {
    return listOf(
        Path.of(".env"),
        Path.of("backend", ".env"),
        Path.of("..", "backend", ".env"),
    )
}

fun parseDotEnv(text: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        if (key.isNotEmpty()) values[key] = value
    }
    return values
}
