# Synapse

An AI-facing window into a running Minecraft client. Synapse runs a small local
HTTP server inside the client JVM so an external AI can **observe** and
**control** the game against ground-truth internal state — JSON, not pixels.

- Minecraft **1.20.1**, **Forge**, client-side only.
- Zero third-party dependencies (HTTP via the JDK, JSON via Minecraft's Gson).
- Self-describing: one call to `GET /manifest` documents the whole API.

## Build

```
./gradlew build
```

The mod jar lands in `build/libs/`. Drop it into a Forge 1.20.1 client.

## Run / use with an AI

1. Launch Minecraft with the mod and **load a singleplayer world**.
2. Type **`/synapse`** in chat.

That's the whole setup — and being able to run `/synapse` proves it: the mod is
loaded, the HTTP server is up, and you're in a world (which is exactly what the
API needs). The command prints this machine's base URL, a clickable link to the
generated `<gamedir>/synapse/AGENT.md`, and `/synapse prompt` copies a
ready-to-paste agent prompt to your clipboard.

Then hand that prompt (or just the base URL) to your AI agent. By design you
barely explain anything — it calls `GET /manifest` and learns the rest. The
prompt:

> You can observe and control a running Minecraft client through a local HTTP
> API ("Synapse") at `http://127.0.0.1:25599`. It returns JSON ground truth,
> not pixels. First call `GET /manifest` — it lists every endpoint, parameter,
> the `/state` schema, error codes, and tips; treat it as the only docs you
> need. Loop: `GET /state?mode=summary` → act (`POST /cmd`, `POST /gui`,
> `POST /player`) → verify with `/state` (and `GET /gui` for screens), using
> `GET /wait?ticks=N` to let changes settle. Every response carries `logs`,
> `context`, and on failure an `error` with code/message/hint — read them and
> self-correct.

Any agent with a shell can drive it via `curl` (this is how the mod is tested).
For a first-class setup, this repo ships an **MCP server** that exposes the
endpoints as tools (`synapse_state`, `synapse_cmd`, …) for Claude Code/Desktop,
Codex, and other MCP clients — see [`mcp/README.md`](mcp/README.md). Synapse is
AI-agnostic: anything that can make HTTP calls (a shell, an HTTP tool, or MCP)
can drive it.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /manifest` | Self-describing capability dump — call once at startup. |
| `GET /state?mode=summary\|full` | Ground-truth player/world state. |
| `POST /cmd` | Run a Minecraft command (level 4); returns captured feedback. |
| `GET /gui` / `POST /gui` | Read the open screen (widgets, container slots); open/close/clickSlot/clickButton/type. |
| `POST /player` | look / move / use / attack / selectHotbar. |
| `GET /wait?ticks=N` | Block until N in-world ticks pass (synchronisation). |
| `GET /chat` / `POST /chat` | Read recent chat / send chat or a command. |
| `POST /batch` | Run several ops in one request (cmd/state/player/gui/chat/wait), in order. |
| `GET /screenshot` | PNG of the frame (visual checks only). |

Every response uses one envelope: `{ ok, endpoint, data, error, logs, context, ts }`.

```
# Auth is on by default — the token is auto-generated and stored machine-local:
TOKEN=$(cat ~/.synapse/token)
H="X-Synapse-Token: $TOKEN"

curl -s -H "$H" 127.0.0.1:25599/manifest | jq
curl -s -H "$H" "127.0.0.1:25599/state?mode=summary" | jq .data
curl -s -H "$H" -X POST 127.0.0.1:25599/cmd -d "give @s minecraft:diamond 64" | jq .data
curl -s -H "$H" -H "Content-Type: application/json" -X POST 127.0.0.1:25599/gui    -d '{"action":"open","target":"inventory"}'
curl -s -H "$H" -H "Content-Type: application/json" -X POST 127.0.0.1:25599/player -d '{"action":"move","forward":true,"ticks":20}'
```

(The bundled MCP server and the `/synapse` command surface the same token, so a
local AI agent needs no manual setup.)

## Multiple instances (one AI, many worlds)

Run Synapse in several Minecraft instances at once and drive them all from one
agent. Each launch:

- **Auto-ports** — if `25599` is busy it binds the next free port (`autoPort`,
  on by default), so instances never collide.
- **Advertises itself** in `~/.synapse/instances.json` (`name`, `port`,
  `baseUrl`, per-launch `instanceId`, `pid`, token), removed on clean exit.

The MCP server discovers them, so every tool takes an optional **`target`** (an
instance name or port); `synapse_list` shows what's running. With one instance,
`target` is optional. Raw HTTP works too — just point at the right port. The
registry is `0600` and entries carry an `instanceId` so a stale entry whose port
was reused is detected rather than silently driven.

## Configuration & security

`synapse-client.toml` (in the config dir): `port`, `autoPort`, `bindAddress`,
`authToken`, `timeoutMs`, `maxBodyBytes`, `batchBudgetMs`, `stateRadius`,
`logBufferSize`, `captureAllModLogs`, `screenshotEnabled`.

The bridge runs *inside your game client*, and `/cmd` executes Minecraft
commands at permission level 4. Treat the port as a control surface and keep it
local. Synapse hardens this in depth:

- **Loopback bind.** Binds to `127.0.0.1` by default. If you change `bindAddress`
  to a non-loopback address *and* have no auth token, Synapse **refuses to
  start** (it would expose level-4 command execution to the network).
- **Auth on by default.** Leave `authToken` empty and Synapse auto-generates a
  strong random token on first run, stored machine-local at `~/.synapse/token`.
  Every request must carry it as `X-Synapse-Token`. The bundled MCP server
  auto-reads that file, and `/synapse` / `AGENT.md` print it, so your local
  agent still works with zero manual steps — but a web page or other process
  that can't read the file is rejected. Set an explicit `authToken` to manage
  your own secret (e.g. for a deliberate LAN setup, where you pass it to the
  client via `SYNAPSE_TOKEN`).
- **Browser / CSRF / DNS-rebinding guard.** On a loopback bind, requests are
  rejected unless the `Host` header is a localhost name on the right port
  (defeats DNS rebinding), no cross-site `Sec-Fetch-Site` is present, and any
  `Origin` is a localhost origin. Mutating JSON endpoints also require
  `Content-Type: application/json` (not a CORS "simple" type). Net effect: a
  malicious web page you have open **cannot** drive the bridge.
- **Resource limits.** Request bodies are capped (`maxBodyBytes`, default 1 MiB)
  and slow/stalled connections are timed out, so a single request can't exhaust
  the client's memory or pin its threads.
- **Minimal disclosure.** By default `logs` carries only Synapse's own log lines
  (set `captureAllModLogs=true` for the whole game's log); rejected requests get
  no `logs`/`context`; the token is never logged.

## License

MIT — see [LICENSE](LICENSE).
