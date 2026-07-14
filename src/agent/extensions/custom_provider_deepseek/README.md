# custom-provider-deepseek

DeepSeek provider — OpenAI-compatible endpoint at `api.deepseek.com/v1`.

DeepSeek-V4 thinking models stream chain-of-thought via the `reasoning_content` delta field,
which the AI SDK's chat-completions adapter doesn't parse. Responses are wrapped through
`agent.utils.reasoning-stream`, rewriting those deltas into inline `<think>…</think>` content
that nyma's think-tag parser already renders.

Requires `DEEPSEEK_API_KEY`.
