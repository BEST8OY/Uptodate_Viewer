# Provider-Specific Parameters

## OpenAI (`OpenAIChatParams`)

Package: `ai.koog.prompt.executor.clients.openai`

| Parameter | Type | Description |
|-----------|------|-------------|
| temperature | Double? | Sampling temp [0.0, 2.0]. Mutually exclusive with topP. |
| maxTokens | Int? | Max tokens to generate |
| topP | Double? | Nucleus sampling (0.0, 1.0]. Mutually exclusive with temperature. |
| frequencyPenalty | Double? | Penalizes frequent tokens [-2.0, 2.0] |
| presencePenalty | Double? | Penalizes reused tokens [-2.0, 2.0] |
| reasoningEffort | ReasoningEffort? | NONE/MINIMAL/LOW/MEDIUM/HIGH |
| store | Boolean? | Allow provider to store outputs |
| stop | List<String>? | Stop sequences (max 4) |
| parallelToolCalls | Boolean? | Allow parallel tool calls |
| logprobs | Boolean? | Include log-probabilities |
| topLogprobs | Int? | Top alternatives per position (0-20, requires logprobs) |
| serviceTier | ServiceTier? | Processing tier selection |
| promptCacheKey | String? | Stable cache key |
| safetyIdentifier | String? | App-scoped user ID |
| audio | OpenAIAudioConfig? | Audio output config |
| webSearchOptions | OpenAIWebSearchOptions? | Web search config |

## MistralAI (`MistralAIParams`)

Package: `ai.koog.prompt.executor.clients.mistralai`

| Parameter | Type | Description |
|-----------|------|-------------|
| temperature | Double? | Sampling temp. Mutually exclusive with topP. |
| maxTokens | Int? | Max tokens to generate |
| topP | Double? | Nucleus sampling (0.0, 1.0] |
| frequencyPenalty | Double? | Penalizes frequent tokens [-2.0, 2.0] |
| presencePenalty | Double? | Penalizes reused tokens [-2.0, 2.0] |
| safePrompt | Boolean? | Inject safety prompt before conversations |
| stop | List<String>? | Stop sequences (max 4) |
| randomSeed | Int? | Seed for deterministic results |
| parallelToolCalls | Boolean? | Allow parallel tool calls |
| promptMode | String? | "reasoning" for chain-of-thought mode |

## Anthropic (`AnthropicParams`)

Package: `ai.koog.prompt.executor.clients.anthropic`

| Parameter | Type | Description |
|-----------|------|-------------|
| temperature | Double? | Sampling temp [0.0, 1.0]. Mutually exclusive with topP. |
| maxTokens | Int? | Max tokens to generate (>= 1) |
| topP | Double? | Nucleus sampling (0.0, 1.0]. Mutually exclusive with temperature. |
| topK | Int? | Sample from top K options (>= 0) |
| stopSequences | List<String>? | Custom stop sequences |
| thinking | AnthropicThinking? | Extended thinking configuration |
| serviceTier | AnthropicServiceTier? | AUTO or STANDARD_ONLY |
| container | String? | Container identifier for reuse |
| mcpServers | List<AnthropicMCPServerURLDefinition>? | MCP servers (max 20) |
| cacheControl | AnthropicCacheControl? | Cache control strategy |

### AnthropicThinking

Sealed interface: `Enabled(budgetTokens: Int)` or `Disabled`

- `Enabled`: requires minimum 1024 tokens, counts towards maxTokens
- `Disabled`: no extended thinking

### AnthropicServiceTier

Enum: `AUTO`, `STANDARD_ONLY`

## Google (`GoogleParams`)

Package: `ai.koog.prompt.executor.clients.google`

| Parameter | Type | Description |
|-----------|------|-------------|
| temperature | Double? | Sampling temp [0.0, 2.0] |
| maxTokens | Int? | Max tokens to generate |
| topP | Double? | Nucleus sampling [0.0, 1.0] |
| topK | Int? | Max tokens to consider when sampling (>= 0) |
| thinkingConfig | GoogleThinkingConfig? | Chain-of-thought configuration |

### GoogleThinkingConfig

Package: `ai.koog.prompt.executor.clients.google.models`

| Parameter | Type | Description |
|-----------|------|-------------|
| includeThoughts | Boolean? | Show intermediate reasoning |
| thinkingBudget | Int? | Token limit for reasoning (Gemini 2.0). Mutually exclusive with thinkingLevel. |
| thinkingLevel | GoogleThinkingLevel? | LOW or HIGH (Gemini 3.0). Mutually exclusive with thinkingBudget. |

### GoogleThinkingLevel

Enum: `LOW`, `HIGH`

## OpenRouter (`OpenRouterParams`)

Package: `ai.koog.prompt.executor.clients.openrouter`

| Parameter | Type | Description |
|-----------|------|-------------|
| temperature | Double? | Sampling temp [0.0, 2.0]. Mutually exclusive with topP. |
| maxTokens | Int? | Max tokens to generate |
| topP | Double? | Nucleus sampling (0.0, 1.0]. Mutually exclusive with temperature. |
| topK | Int? | Top tokens to consider (>= 1) |
| frequencyPenalty | Double? | Penalizes frequent tokens [-2.0, 2.0] |
| presencePenalty | Double? | Penalizes reused tokens [-2.0, 2.0] |
| repetitionPenalty | Double? | Penalizes token repetition (0.0, 2.0] |
| minP | Double? | Minimum cumulative probability for token inclusion [0.0, 1.0] |
| topA | Double? | Temperature scaling based on marginal probability gain [0.0, 1.0] |
| stop | List<String>? | Stop sequences (max 4) |
| logprobs | Boolean? | Include log-probabilities |
| topLogprobs | Int? | Top alternatives per position (0-20, requires logprobs) |
| transforms | List<String>? | Context transforms (e.g. ["middle-out"]) |
| models | List<String>? | Allowed models for this request |
| route | String? | Request routing identifier |
| provider | ProviderPreferences? | Model provider preferences |

### ProviderPreferences

Package: `ai.koog.prompt.executor.clients.openrouter.models`

| Parameter | Type | Description |
|-----------|------|-------------|
| order | List<String>? | Provider slugs to try in order |
| allowFallbacks | Boolean? | Allow backup providers when primary unavailable |
| requireParameters | Boolean? | Only use providers supporting all parameters |
| dataCollection | String? | Control data collection providers |
| only | List<String>? | Allowlist of provider slugs |
| ignore | List<String>? | Blocklist of provider slugs |
| quantizations | List<String>? | Filter by quantization levels |
| sort | String? | Sort by "price" or "throughput" |
| maxPrice | Map<String, String>? | Maximum pricing per request |

## ReasoningEffort (OpenAI)

Package: `ai.koog.prompt.executor.clients.openai.base.models`

Enum: `NONE`, `MINIMAL`, `LOW`, `MEDIUM`, `HIGH`
