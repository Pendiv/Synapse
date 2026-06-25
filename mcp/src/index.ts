/**
 * Synapse MCP server — exposes the Synapse Minecraft HTTP bridge
 * (default http://127.0.0.1:25599) as MCP tools, so any MCP client
 * (Claude Code/Desktop, Codex, ...) can observe and control the game.
 *
 * Config via env:
 *   SYNAPSE_URL   base URL of the bridge (default http://127.0.0.1:25599)
 *   SYNAPSE_TOKEN auth token, sent as X-Synapse-Token. If unset, the token is
 *                 auto-discovered from ~/.synapse/token (where the mod writes the
 *                 auto-generated token), so the local setup needs zero manual steps.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { z } from "zod";

const BASE = (process.env.SYNAPSE_URL ?? "http://127.0.0.1:25599").replace(/\/+$/, "");

/** Token from env, else the machine-local file the mod writes (~/.synapse/token). */
function resolveToken(): string {
  const fromEnv = process.env.SYNAPSE_TOKEN;
  if (fromEnv && fromEnv.length > 0) return fromEnv;
  try {
    return readFileSync(join(homedir(), ".synapse", "token"), "utf8").trim();
  } catch {
    return "";
  }
}
const TOKEN = resolveToken();

type Json = any;

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return TOKEN ? { ...extra, "X-Synapse-Token": TOKEN } : { ...extra };
}

/** One HTTP call to the bridge. Returns parsed JSON (or {raw,status} for non-JSON). */
async function call(method: string, path: string, body?: string | object): Promise<Json> {
  const init: RequestInit = { method, headers: authHeaders() };
  if (body !== undefined) {
    if (typeof body === "string") {
      init.body = body;
    } else {
      init.body = JSON.stringify(body);
      init.headers = authHeaders({ "Content-Type": "application/json" });
    }
  }
  const res = await fetch(BASE + path, init);
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text, httpStatus: res.status };
  }
}

const text = (data: Json) => ({ content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }] });

const unreachable = (e: unknown) => ({
  content: [{
    type: "text" as const,
    text:
      `Could not reach Synapse at ${BASE} (${String(e)}).\n` +
      `Is Minecraft running with the Synapse mod, and are you in a world? ` +
      `In-game, type /synapse to confirm.`,
  }],
  isError: true,
});

/** Wrap a tool body so connection errors become a helpful message instead of a crash. */
async function guard(fn: () => Promise<Json>) {
  try {
    return text(await fn());
  } catch (e) {
    return unreachable(e);
  }
}

const server = new McpServer({ name: "synapse", version: "1.0.0" });

server.registerTool(
  "synapse_manifest",
  {
    title: "Synapse: manifest",
    description:
      "Self-describing capability dump: every endpoint, parameter, the state schema, error codes, and tips. " +
      "Call this first to learn the full API.",
    inputSchema: {},
  },
  async () => guard(() => call("GET", "/manifest")),
);

server.registerTool(
  "synapse_state",
  {
    title: "Synapse: state",
    description: "Ground-truth player/world state (position, look, inventory, nearby entities, world). " +
      "Primary observation tool. NOT_IN_WORLD if on a menu.",
    inputSchema: {
      mode: z.enum(["summary", "full"]).optional().describe("summary (default) or full"),
    },
  },
  async ({ mode }) => guard(() => call("GET", `/state?mode=${mode ?? "summary"}`)),
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
    },
  },
  async ({ command }) => guard(() => call("POST", "/cmd", command)),
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
    },
  },
  async ({ action, params }) => guard(() =>
    action ? call("POST", "/gui", { action, ...(params ?? {}) }) : call("GET", "/gui")),
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
    },
  },
  async ({ action, params }) => guard(() => call("POST", "/player", { action, ...(params ?? {}) })),
);

server.registerTool(
  "synapse_wait",
  {
    title: "Synapse: wait",
    description: "Block until N in-world ticks pass (20 ticks = 1s). Use to let an action settle before observing.",
    inputSchema: {
      ticks: z.number().int().min(1).max(600).describe("Ticks to wait (1..600)."),
    },
  },
  async ({ ticks }) => guard(() => call("GET", `/wait?ticks=${ticks}`)),
);

server.registerTool(
  "synapse_chat",
  {
    title: "Synapse: chat",
    description: "With no 'text': read recent received chat. With 'text': send chat (or a command if it starts with '/').",
    inputSchema: {
      text: z.string().optional().describe("Omit to read recent chat; otherwise the message to send."),
    },
  },
  async ({ text: msg }) => guard(() =>
    msg === undefined ? call("GET", "/chat") : call("POST", "/chat", { text: msg })),
);

server.registerTool(
  "synapse_screenshot",
  {
    title: "Synapse: screenshot",
    description: "Capture the current frame as a PNG image (visual sanity check only; prefer synapse_state for logic).",
    inputSchema: {},
  },
  async () => {
    try {
      const data = await call("GET", "/screenshot?format=base64");
      const img = data?.data?.image;
      if (typeof img !== "string") {
        return text(data);
      }
      return { content: [{ type: "image" as const, data: img, mimeType: "image/png" }] };
    } catch (e) {
      return unreachable(e);
    }
  },
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  // stdout is the JSON-RPC channel — logs must go to stderr.
  console.error(`[synapse-mcp] connected; bridging ${BASE} (auth: ${TOKEN ? "on" : "off"})`);
}

main().catch((e) => {
  console.error("[synapse-mcp] fatal:", e);
  process.exit(1);
});
