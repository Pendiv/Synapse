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
For a polished setup, wrap the endpoints as MCP tools.

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
| `GET /screenshot` | PNG of the frame (visual checks only). |

Every response uses one envelope: `{ ok, endpoint, data, error, logs, context, ts }`.

```
curl -s 127.0.0.1:25599/manifest | jq
curl -s "127.0.0.1:25599/state?mode=summary" | jq .data
curl -s -X POST 127.0.0.1:25599/cmd    -d "give @s minecraft:diamond 64" | jq .data
curl -s -X POST 127.0.0.1:25599/gui    -d '{"action":"open","target":"inventory"}'
curl -s -X POST 127.0.0.1:25599/player -d '{"action":"move","forward":true,"ticks":20}'
```

## Configuration & security

`synapse-client.toml` (in the config dir): `port`, `bindAddress`, `authToken`,
`timeoutMs`, `stateRadius`, `logBufferSize`, `screenshotEnabled`.

- Binds to `127.0.0.1` by default. Anyone who can reach the port can run
  arbitrary commands, so keep it on loopback.
- Set `authToken` to require an `X-Synapse-Token` header on every request.
  Empty token = auth disabled (a warning is logged at startup).

## License

MIT — see [LICENSE](LICENSE).
