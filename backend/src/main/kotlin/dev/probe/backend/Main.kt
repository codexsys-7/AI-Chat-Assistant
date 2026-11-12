package dev.probe.backend

fun main() {
    val config = BackendConfig.load()
    val handler = CompletionHandler(
        model = OpenAiLanguageModel(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = config.baseUrl,
        ),
        modelName = config.model,
    )
    val embeddings = EmbeddingHandler(
        model = OpenAiEmbeddingModel(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
        ),
    )
    val server = startCompletionServer(config.port, handler, embeddings = embeddings)
    val keyState = if (config.apiKey.isBlank()) "missing" else "set"
    println(
        "AI backend listening on 0.0.0.0:${server.address.port} model=${config.model} " +
            "embeddings=$EMBEDDING_MODEL dimensions=$EMBEDDING_DIMENSIONS key=$keyState",
    )
    Thread.currentThread().join()
}
