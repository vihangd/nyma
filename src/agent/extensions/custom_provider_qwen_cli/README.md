# custom-provider-qwen-cli

> OAuth-backed Qwen CLI provider — device-code login, automatic token refresh, OpenAI-compatible API.

## What it does

Registers a `qwen-cli` provider that lets nyma talk to Alibaba's Qwen models through the **Qwen CLI** OAuth flow. On first use it walks the user through the OAuth 2.0 **device code** flow, opens the browser to the Qwen login page, and stores the resulting token. Subsequent calls refresh the access token automatically.

Supports Qwen3 Coder Plus / Flash and Qwen3 VL Plus, all routed through `dashscope.aliyuncs.com` over the OpenAI-compatible API surface — so once authenticated it behaves like any other OpenAI-shaped provider in nyma.

## How to use

```
/login qwen-cli
```

(or select `qwen-cli` from `/model`'s provider picker). Follow the device-code prompt that appears in the UI.

## Capabilities

`providers`, `model`

## See also

- [Qwen Coder API docs](https://help.aliyun.com/zh/dashscope/) (Alibaba)
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — provider authoring guide
