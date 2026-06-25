# synapse-mcp

An [MCP](https://modelcontextprotocol.io) server that exposes the Synapse
Minecraft bridge as tools, so any MCP client (Claude Code/Desktop, Codex, …) can
drive the game without speaking raw HTTP.

```
AI client ⇄ synapse-mcp (this) ⇄ HTTP ⇄ Synapse mod ⇄ Minecraft
```

## Tools

`synapse_manifest`, `synapse_state`, `synapse_cmd`, `synapse_gui`,
`synapse_player`, `synapse_wait`, `synapse_chat`, `synapse_screenshot`
(returns the frame as an image). They are thin proxies onto the bridge's
endpoints; the model should call `synapse_manifest` first to learn parameter
shapes.

## Build

```
cd mcp
npm install
npm run build      # -> build/index.js
```

## Use with Claude Code

A project-scoped `.mcp.json` is committed at the repo root, so running `claude`
from the repo already offers the `synapse` server (after `npm run build`).
Otherwise register it explicitly:

```
claude mcp add synapse -- node /abs/path/to/mcp/build/index.js
```

For **Claude Desktop**, add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "synapse": { "command": "node", "args": ["/abs/path/to/mcp/build/index.js"] }
  }
}
```

Other MCP clients take the same `command`/`args`.

## Config (env)

| Var | Default | Meaning |
|---|---|---|
| `SYNAPSE_URL` | `http://127.0.0.1:25599` | Bridge base URL |
| `SYNAPSE_TOKEN` | *(none)* | Sent as `X-Synapse-Token` if set |

## Prerequisites

Minecraft must be running with the Synapse mod **and you must be in a world**
(in-game, type `/synapse` to confirm). Tool calls return a clear "could not
reach Synapse" message otherwise.
