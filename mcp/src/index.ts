/**
 * Synapse MCP server — exposes the Synapse Minecraft HTTP bridge as MCP tools, so
 * any MCP client (Claude Code/Desktop, Codex, ...) can observe and control the game.
 *
 * Multi-instance: each running Minecraft advertises its bridge in
 * ~/.synapse/instances.json. This server discovers them, so one AI can drive several
 * environments at once — pass `target` (an instance name or port) to any tool, or call
 * `synapse_list` to see what is running. With a single instance, `target` is optional.
 *
 * Config via env (override discovery for a fixed single target):
 *   SYNAPSE_URL   base URL of the bridge (default: discovered, else http://127.0.0.1:25599)
 *   SYNAPSE_TOKEN auth token, sent as X-Synapse-Token (default: discovered, else ~/.synapse/token)
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { z } from "zod";

type Json = any;

const SYN_DIR = join(homedir(), ".synapse");
const ENV_URL = process.env.SYNAPSE_URL;
const ENV_TOKEN = process.env.SYNAPSE_TOKEN;

interface Instance {
  name: string;
  instanceId?: string;
  gamedir?: string;
  port: number;
  baseUrl: string;
  token?: string;
  mcVersion?: string;
}

interface Target {
  baseUrl: string;
  token: string;
  label: string;
}

/** A resolution problem (no/ambiguous target) — distinct from a connection failure. */
class TargetError extends Error {}

/** Token from the machine-local file the mod writes (used when an instance has none of its own). */
function readToken(): string {
  try {
    return readFileSync(join(SYN_DIR, "token"), "utf8").trim();
  } catch {
    return "";
  }
}

/** Live list of advertised instances (re-read each call, so start/stop is picked up). */
function readInstances(): Instance[] {
  try {
    const raw = JSON.parse(readFileSync(join(SYN_DIR, "instances.json"), "utf8"));
    const arr = Array.isArray(raw?.instances) ? raw.instances : [];
    return arr.filter((i: any) => i && typeof i.port === "number" && typeof i.baseUrl === "string");
  } catch {
    return [];
  }
}

function pickToken(own?: string): string {
  return own ?? ENV_TOKEN ?? readToken();
}

/** Resolve which bridge a call goes to. `target` may be an instance name, a port, or a URL. */
function resolve(target?: string): Target {
  const instances = readInstances();

  if (target) {
    const t = String(target);
    const m = instances.find((i) => i.name === t || String(i.port) === t || i.baseUrl === t);
    if (m) return { baseUrl: m.baseUrl, token: pickToken(m.token), label: m.name };
    if (/^https?:\/\//i.test(t)) return { baseUrl: t.replace(/\/+$/, ""), token: pickToken(), label: t };
    if (/^\d+$/.test(t)) return { baseUrl: `http://127.0.0.1:${t}`, token: pickToken(), label: `:${t}` };
    const known = instances.map((i) => `${i.name}(:${i.port})`).join(", ") || "none";
    throw new TargetError(`No Synapse instance "${t}". Running: ${known}. Call synapse_list.`);
  }

  if (ENV_URL) return { baseUrl: ENV_URL.replace(/\/+$/, ""), token: pickToken(), label: ENV_URL };
  if (instances.length === 1) {
    const i = instances[0];
    return { baseUrl: i.baseUrl, token: pickToken(i.token), label: i.name };
  }
  if (instances.length === 0) return { baseUrl: "http://127.0.0.1:25599", token: pickToken(), label: "127.0.0.1:25599" };

  const known = instances.map((i) => `${i.name}(:${i.port})`).join(", ");
  throw new TargetError(
    `${instances.length} Synapse instances are running — pass 'target' (name or port). Running: ${known}. Call synapse_list.`,
  );
}

/** One HTTP call to a resolved bridge. Returns parsed JSON (or {raw,status} for non-JSON). */
async function call(method: string, path: string, t: Target, body?: string | object): Promise<Json> {
  const headers: Record<string, string> = t.token ? { "X-Synapse-Token": t.token } : {};
  const init: RequestInit = { method, headers };
  if (body !== undefined) {
    if (typeof body === "string") {
      init.body = body;
    } else {
      init.body = JSON.stringify(body);
      init.headers = { ...headers, "Content-Type": "application/json" };
    }
  }
  const res = await fetch(t.baseUrl + path, init);
  const txt = await res.text();
  try {
    return JSON.parse(txt);
  } catch {
    return { raw: txt, httpStatus: res.status };
  }
}

const text = (data: Json) => ({ content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }] });

const targetErr = (e: unknown) => ({
  content: [{ type: "text" as const, text: String((e as Error).message) }],
  isError: true,
});

const unreachable = (baseUrl: string, e: unknown) => ({
  content: [{
    type: "text" as const,
    text:
      `Could not reach Synapse at ${baseUrl} (${String(e)}).\n` +
      `Is that Minecraft instance running with the Synapse mod, and are you in a world? ` +
      `Call synapse_list to see running instances.`,
  }],
  isError: true,
});

/** Resolve the target, run the call, and turn resolution/connection problems into clear messages. */
async function guard(target: string | undefined, fn: (t: Target) => Promise<Json>) {
  let t: Target;
  try {
    t = resolve(target);
  } catch (e) {
    return targetErr(e);
  }
  try {
    return text(await fn(t));
  } catch (e) {
    return unreachable(t.baseUrl, e);
  }
}

const TARGET = z
  .string()
  .optional()
  .describe("Which Synapse instance (name or port) when several run; omit if only one. See synapse_list.");

const server = new McpServer({ name: "synapse", version: "1.2.0" });

server.registerTool(
  "synapse_list",
  {
    title: "Synapse: list instances",
    description:
      "List running Synapse instances (name, port, reachable). When several environments run, pass a name or " +
      "port as 'target' to the other tools to choose one. With a single instance, 'target' can be omitted.",
    inputSchema: {},
  },
  async () => {
    const instances = readInstances();
    const checked = await Promise.all(
      instances.map(async (i) => {
        const tok = pickToken(i.token);
        let reachable = false;
        let stale: string | undefined;
        try {
          const res = await fetch(i.baseUrl + "/manifest", { headers: tok ? { "X-Synapse-Token": tok } : {} });
          reachable = res.ok;
          if (res.ok) {
            // Detect a stale entry whose port was reused by a different live instance.
            const live = (await res.json())?.data?.instance;
            if (live && i.instanceId && live.instanceId && live.instanceId !== i.instanceId) {
              stale = `port ${i.port} now serves "${live.name}" (different instance) — entry for "${i.name}" is stale`;
            }
          }
        } catch {
          reachable = false;
        }
        return { name: i.name, port: i.port, baseUrl: i.baseUrl, mcVersion: i.mcVersion, reachable, ...(stale ? { stale } : {}) };
      }),
    );
    return text({
      count: checked.length,
      instances: checked,
      note:
        checked.length > 1
          ? "Several instances — pass 'target' (name or port) to the other tools."
          : checked.length === 0
            ? "No instances advertised; tools fall back to http://127.0.0.1:25599 (or SYNAPSE_URL)."
            : undefined,
    });
  },
);

server.registerTool(
  "synapse_manifest",
  {
    title: "Synapse: manifest",
    description:
      "Self-describing capability dump: every endpoint, parameter, the state schema, error codes, the instance " +
      "name, and tips. Call this first to learn the full API.",
    inputSchema: { target: TARGET },
  },
  async ({ target }) => guard(target, (t) => call("GET", "/manifest", t)),
);

server.registerTool(
  "synapse_state",
  {
    title: "Synapse: state",
    description:
      "Ground-truth player/world state (position, look, inventory, nearby entities, world). " +
      "Primary observation tool. NOT_IN_WORLD if on a menu.",
    inputSchema: {
      mode: z.enum(["summary", "full"]).optional().describe("summary (default) or full"),
      target: TARGET,
    },
  },
  async ({ mode, target }) => guard(target, (t) => call("GET", `/state?mode=${mode ?? "summary"}`, t)),
);

server.registerTool(
  "synapse_cmd",
  {
    title: "Synapse: command",
    description:
      "Run a Minecraft command at permission level 4 (leading slash optional). Returns captured feedback and " +
      "the command's success. e.g. 'give @s minecraft:diamond 64', 'tp @s 0 100 0', 'time set day'.",
    inputSchema: {
      command: z.string().describe("The command text, without a leading slash."),
      target: TARGET,
    },
  },
  async ({ command, target }) => guard(target, (t) => call("POST", "/cmd", t, command)),
);

server.registerTool(
  "synapse_gui",
  {
    title: "Synapse: gui",
    description:
      "Observe or drive the open screen. With no 'action': reads the screen (widgets + container slots). " +
      "With 'action': open|close|clickSlot|clickButton|type — put that action's fields in 'params' " +
      "(see synapse_manifest /gui for shapes, e.g. {action:'open', params:{target:'inventory'}}).",
    inputSchema: {
      action: z.enum(["open", "close", "clickSlot", "clickButton", "type"]).optional()
        .describe("Omit to observe; otherwise the GUI action."),
      params: z.record(z.any()).optional().describe("Fields for the action, e.g. {target:'inventory'} or {slot:0}."),
      target: TARGET,
    },
  },
  async ({ action, params, target }) =>
    guard(target, (t) => (action ? call("POST", "/gui", t, { action, ...(params ?? {}) }) : call("GET", "/gui", t))),
);

server.registerTool(
  "synapse_player",
  {
    title: "Synapse: player",
    description:
      "Physically control the player: look/move/use/attack/selectHotbar. Interactions target whatever the " +
      "player looks at. e.g. {action:'look', params:{yaw:90,pitch:0}}, {action:'move', params:{forward:true,ticks:20}}.",
    inputSchema: {
      action: z.enum(["look", "move", "use", "attack", "selectHotbar"]).describe("The player action."),
      params: z.record(z.any()).optional().describe("Fields for the action."),
      target: TARGET,
    },
  },
  async ({ action, params, target }) => guard(target, (t) => call("POST", "/player", t, { action, ...(params ?? {}) })),
);

server.registerTool(
  "synapse_wait",
  {
    title: "Synapse: wait",
    description: "Block until N in-world ticks pass (20 ticks = 1s). Use to let an action settle before observing.",
    inputSchema: {
      ticks: z.number().int().min(1).max(600).describe("Ticks to wait (1..600)."),
      target: TARGET,
    },
  },
  async ({ ticks, target }) => guard(target, (t) => call("GET", `/wait?ticks=${ticks}`, t)),
);

server.registerTool(
  "synapse_chat",
  {
    title: "Synapse: chat",
    description: "With no 'text': read recent received chat. With 'text': send chat (or a command if it starts with '/').",
    inputSchema: {
      text: z.string().optional().describe("Omit to read recent chat; otherwise the message to send."),
      target: TARGET,
    },
  },
  async ({ text: msg, target }) =>
    guard(target, (t) => (msg === undefined ? call("GET", "/chat", t) : call("POST", "/chat", t, { text: msg }))),
);

server.registerTool(
  "synapse_batch",
  {
    title: "Synapse: batch",
    description:
      "Run several operations in ONE request, in order — cuts observe/act/verify round-trips. Each op is one " +
      "of: {op:'cmd', command} | {op:'state', mode} | {op:'player', action, ...} | {op:'gui', action?, ...} | " +
      "{op:'chat', text?} | {op:'wait', ticks}. Returns { results:[{index,op,ok,data|error}], count, ranAll }.",
    inputSchema: {
      ops: z.array(z.record(z.any())).describe("Ordered ops, e.g. [{op:'cmd',command:'...'},{op:'wait',ticks:2},{op:'state'}]."),
      stopOnError: z.boolean().optional().describe("Abort remaining ops after the first failure (default false)."),
      target: TARGET,
    },
  },
  async ({ ops, stopOnError, target }) =>
    guard(target, (t) => call("POST", "/batch", t, stopOnError === undefined ? { ops } : { ops, stopOnError })),
);

server.registerTool(
  "synapse_screenshot",
  {
    title: "Synapse: screenshot",
    description: "Capture the current frame as a PNG image (visual sanity check only; prefer synapse_state for logic).",
    inputSchema: { target: TARGET },
  },
  async ({ target }) => {
    let t: Target;
    try {
      t = resolve(target);
    } catch (e) {
      return targetErr(e);
    }
    try {
      const data = await call("GET", "/screenshot?format=base64", t);
      const img = data?.data?.image;
      if (typeof img !== "string") {
        return text(data);
      }
      return { content: [{ type: "image" as const, data: img, mimeType: "image/png" }] };
    } catch (e) {
      return unreachable(t.baseUrl, e);
    }
  },
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // stdout is the JSON-RPC channel — logs must go to stderr.
  const n = readInstances().length;
  console.error(
    `[synapse-mcp] connected; ${n} instance(s) discovered in ~/.synapse/instances.json` +
      (ENV_URL ? ` (env override: ${ENV_URL})` : ""),
  );
}

main().catch((e) => {
  console.error("[synapse-mcp] fatal:", e);
  process.exit(1);
});
