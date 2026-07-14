# custom-provider-kimi

Moonshot/Kimi provider with thinking-model passthrough.

K2 thinking models stream chain-of-thought through Moonshot-specific delta fields
(`reasoning_content` on the older line, `reasoning` on K2.6) that the AI SDK's chat-completions
adapter doesn't recognize. The shared `agent.utils.reasoning-stream` helper handles the response
side; this extension adds the Moonshot-specific request-side rewrites.

Requires `MOONSHOT_API_KEY`.
