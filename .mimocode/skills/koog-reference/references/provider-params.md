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

## ReasoningEffort (OpenAI)

Package: `ai.koog.prompt.executor.clients.openai.base.models`

Enum: `NONE`, `MINIMAL`, `LOW`, `MEDIUM`, `HIGH`
