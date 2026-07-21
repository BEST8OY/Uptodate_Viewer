---
name: koog-reference
description: >
  Consult the Koog AI library source code, API reference, and documentation.
  Use when: looking up Koog class/function signatures, finding source code for
  a Koog class, checking provider-specific params, understanding agent builder
  API, verifying streaming/event handler APIs, or checking version differences.
  Covers github.com/JetBrains/koog, api.koog.ai, and docs.koog.ai.
---

# Koog Reference

## Three sources, correct URLs

### 1. GitHub source code (for implementation details)

**View file:**
```
https://github.com/JetBrains/koog/blob/{COMMIT}/{path}
```

**Raw content (for webfetch):**
```
https://raw.githubusercontent.com/JetBrains/koog/{COMMIT}/{path}
```

**Current commit:** `164f57a71ca63fafd1a7dedd2d579c80accf79ca`

**Common source paths:**
| What | Path |
|------|------|
| AbstractOpenAILLMClient | `prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/AbstractOpenAILLMClient.kt` |
| OpenAIChatParams | `prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/OpenAIParams.kt` |
| MistralAIParams | `prompt/prompt-executor/prompt-executor-clients/prompt-executor-mistralai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/mistralai/MistralAIParams.kt` |
| GoogleParams | `prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/GoogleParams.kt` |
| MistralAILLMClient | `prompt/prompt-executor/prompt-executor-clients/prompt-executor-mistralai-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/mistralai/MistralAILLMClient.kt` |
| AIAgentBuilder | `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/AIAgentBuilder.kt` |
| AIAgentServiceBuilderBase | `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/agent/AIAgentServiceBuilderBase.kt` |
| EventHandler | `agents/agents-features/agents-features-event-handler/src/commonMain/kotlin/ai/koog/agents/features/eventHandler/feature/EventHandler.kt` |
| LLMCallEventContext | `agents/agents-core/src/commonMain/kotlin/ai/koog/agents/core/feature/handler/llm/LLMCallEventContext.kt` |
| KoogHttpClient | `http-client/http-client-core/src/commonMain/kotlin/ai/koog/http/client/KoogHttpClient.kt` |
| OkHttpKoogHttpClient | `http-client/http-client-okhttp/src/main/kotlin/ai/koog/http/client/okhttp/OkHttpKoogHttpClient.kt` |
| ReasoningEffort | `prompt/prompt-executor/prompt-executor-clients/prompt-executor-clients-openai-base/src/commonMain/kotlin/ai/koog/prompt/executor/clients/openai/base/models/ReasoningEffort.kt` |
| GoogleThinkingConfig | `prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/src/commonMain/kotlin/ai/koog/prompt/executor/clients/google/models/GoogleGenerateContent.kt` |
| AdditionalPropertiesFlatteningSerializer | `prompt/prompt-executor/prompt-executor-clients/src/commonMain/kotlin/ai/koog/prompt/executor/clients/serialization/AdditionalPropertiesFlatteningSerializer.kt` |

### 2. API reference (for class/method signatures)

**Base URL:** `https://api.koog.ai`

**URL pattern:**
```
https://api.koog.ai/{module}/{package}/{class-or-function}/index.html
```

**Examples:**
| What | URL |
|------|-----|
| OpenAILLMClient | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/ai.koog.prompt.executor.clients.openai/-open-a-i-l-l-m-client/index.html` |
| OpenAIChatParams | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/ai.koog.prompt.executor.clients.openai/-open-a-i-chat-params/index.html` |
| MistralAIParams | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-mistralai-client/ai.koog.prompt.executor.clients.mistralai/-mistral-a-i-params/index.html` |
| GoogleParams | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/ai.koog.prompt.executor.clients.google/-google-params/index.html` |
| ReasoningEffort | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/ai.koog.prompt.executor.clients.openai.base.models/-reasoning-effort/index.html` |
| OpenAIModels | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/ai.koog.prompt.executor.clients.openai/-open-a-i-models/index.html` |
| GoogleModels | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/ai.koog.prompt.executor.clients.google/-google-models/index.html` |
| MistralAIModels | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-mistralai-client/ai.koog.prompt.executor.clients.mistralai/-mistral-a-i-models/index.html` |

**Package index pages:**
| Module | URL |
|--------|-----|
| OpenAI client | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client/ai.koog.prompt.executor.clients.openai/index.html` |
| MistralAI client | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-mistralai-client/ai.koog.prompt.executor.clients.mistralai/index.html` |
| Google client | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-google-client/ai.koog.prompt.executor.clients.google/index.html` |
| OpenAI base | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-openai-client-base/ai.koog.prompt.executor.clients.openai.base/index.html` |
| LLM params | `https://api.koog.ai/prompt/prompt-executor/prompt-executor-clients/prompt-executor-llms-all/ai.koog.prompt.executor.llms.all/index.html` |

### 3. Documentation (for guides and tutorials)

**Base URL:** `https://docs.koog.ai`

**Topics:**
| Topic | URL |
|-------|-----|
| Streaming API | `https://docs.koog.ai/streaming-api/` |
| LLM Parameters | `https://docs.koog.ai/llm-parameters/` |
| History Compression | `https://docs.koog.ai/history-compression/` |
| Chat Memory | `https://docs.koog.ai/chat-memory/` |
| Event Handlers | `https://docs.koog.ai/event-handlers/` |
| Tools | `https://docs.koog.ai/tools/` |
| Quickstart | `https://docs.koog.ai/quickstart/` |

## Version info

Check `gradle/libs.versions.toml` in the project for current versions.
As of last update: core `1.1.1`, beta clients `1.1.1-beta`.

## Gotchas

1. **GitHub raw URLs need commit hash** — `refs/heads/main` doesn't work for raw content. Always use the full commit hash.
2. **API doc URLs use mangled class names** — e.g., `OpenAILLMClient` becomes `-open-a-i-l-l-m-client` (lowercase with hyphens, double-l for "LL").
3. **`internal` classes** — Some classes (like `MistralAIChatCompletionRequestSerializer`) are `internal` and won't appear in API docs.
4. **`sealed interface` vs `sealed class`** — Provider params use `sealed class` (OpenAIChatParams extends LLMParams), not `sealed interface`.
5. **Mutual exclusivity** — `OpenAIChatParams` and `MistralAIParams` throw if both `temperature` and `topP` are set. Always null out one when setting the other.
6. **`text/plain` body bug** — `AbstractOpenAILLMClient.getResponse()` sends body as `text/plain` (via `String::class` type param). OpenAI accepts this; MistralAI rejects it with 422.
