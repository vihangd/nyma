// Loaded before every test file (see bunfig.toml [test] preload).
//
// Several tests load every built-in extension. custom-provider-relay discovers
// its model list over the network and reads the key from the ambient
// environment, so on a machine that exports YUNWU_API_KEY (or any other gateway
// key) a plain `bun test` would make live calls to a third party. Tests that
// exercise discovery stub `fetch` and opt back in explicitly.
process.env.NYMA_NO_MODEL_DISCOVERY ??= "1";
