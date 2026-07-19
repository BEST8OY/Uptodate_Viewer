package com.clinref.app.domain.ai

enum class AiProvider(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GOOGLE("Google"),
    OPENROUTER("OpenRouter"),
    OLLAMA("Ollama (Local)")
}
