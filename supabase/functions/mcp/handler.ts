// Stateless MCP over Streamable HTTP: hand-rolled JSON-RPC 2.0, JSON responses
// only (no SSE stream, no sessions).
//
// Origin: the MCP spec asks servers to validate `Origin` to stop DNS-rebinding
// attacks on *local* servers. This one is remote and every request needs a
// bearer token a browser page cannot obtain, so Origin is not checked.

import { executeTool, isToolName, type Db, TOOLS, ToolInputError } from "./tools.ts";
import { isValidTimeZone } from "./timezone.ts";

export const SUPPORTED_PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"];
export const LATEST_PROTOCOL_VERSION = "2025-06-18";
export const SERVER_INFO = { name: "personal-context", version: "0.1.0" };

const INSTRUCTIONS =
  "Personal context for one user, synced from their phone: daily snapshots (sleep, steps, " +
  "top apps, places, the active episode, the next calendar event, recent notes) and " +
  "episodes (distilled spans of activity such as meetings, commutes or workouts). This is a " +
  "privacy-filtered slice: only data the user marked shareable (sensitivity <= 1) ever " +
  "leaves the phone, so absence of something here does not mean it did not happen. Dates " +
  "are local calendar days in the user's time zone. Start with get_state_now for 'right " +
  "now' questions, get_day for a specific date, and search_episodes to find past activity. " +
  "record_note saves a note to the cloud only (it is not sent back to the phone).";

const PARSE_ERROR = -32700;
const INVALID_REQUEST = -32600;
const METHOD_NOT_FOUND = -32601;
const INVALID_PARAMS = -32602;
const INTERNAL_ERROR = -32603;

/** Max request body; tool inputs are small (notes cap at 20k chars). */
const MAX_BODY_BYTES = 1_000_000;

export interface HandlerOptions {
  db: Db;
  /** Bearer token clients must send. Unset/empty → every request is 500 (fail closed). */
  mcpToken: string | undefined;
  /** IANA zone for local dates. Defaults to "UTC". Invalid → every request is 500. */
  timeZone?: string;
}

type Id = string | number | null;

interface JsonRpcResponse {
  jsonrpc: "2.0";
  id: Id;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

class RpcError extends Error {
  constructor(readonly code: number, message: string) {
    super(message);
  }
}

const encoder = new TextEncoder();

/**
 * Constant-time string comparison. Both sides are hashed first so the
 * comparison loop never depends on (or leaks) the secret's length.
 */
export async function timingSafeEqual(a: string, b: string): Promise<boolean> {
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(a)),
    crypto.subtle.digest("SHA-256", encoder.encode(b)),
  ]);
  const x = new Uint8Array(ha);
  const y = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function rpcError(id: Id, code: number, message: string): JsonRpcResponse {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function validId(v: unknown): v is string | number {
  return typeof v === "string" || (typeof v === "number" && Number.isFinite(v));
}

export function createHandler(opts: HandlerOptions): (req: Request) => Promise<Response> {
  const { db, mcpToken } = opts;
  const timeZone = opts.timeZone || "UTC";
  const configError = !mcpToken
    ? "server misconfigured: MCP_TOKEN is not set"
    : !isValidTimeZone(timeZone)
    ? "server misconfigured: TIMEZONE is not a valid IANA time zone"
    : null;

  async function callTool(params: Record<string, unknown>): Promise<unknown> {
    const name = params.name;
    if (typeof name !== "string") throw new RpcError(INVALID_PARAMS, "params.name must be a string");
    if (!isToolName(name)) throw new RpcError(INVALID_PARAMS, `Unknown tool: ${name}`);
    const args = params.arguments ?? {};
    if (!isObject(args)) throw new RpcError(INVALID_PARAMS, "params.arguments must be an object");
    try {
      const result = await executeTool(name, args, { db, timeZone });
      return {
        content: [{ type: "text", text: JSON.stringify(result) }],
        structuredContent: result,
        isError: false,
      };
    } catch (e) {
      const message = e instanceof ToolInputError
        ? `Invalid arguments for ${name}: ${e.message}`
        : `${name} failed: ${e instanceof Error ? e.message : "unknown error"}. Try again later.`;
      if (!(e instanceof ToolInputError)) {
        // Log only the tool name and error message, never arguments or results.
        console.error(`tool ${name} failed:`, e instanceof Error ? e.message : String(e));
      }
      return { content: [{ type: "text", text: message }], isError: true };
    }
  }

  async function dispatch(method: string, params: unknown): Promise<unknown> {
    if (params !== undefined && !isObject(params)) {
      throw new RpcError(INVALID_PARAMS, "params must be an object");
    }
    const p = (params ?? {}) as Record<string, unknown>;
    switch (method) {
      case "initialize": {
        const requested = p.protocolVersion;
        const protocolVersion =
          typeof requested === "string" && SUPPORTED_PROTOCOL_VERSIONS.includes(requested)
            ? requested
            : LATEST_PROTOCOL_VERSION;
        return {
          protocolVersion,
          capabilities: { tools: { listChanged: false } },
          serverInfo: SERVER_INFO,
          instructions: INSTRUCTIONS,
        };
      }
      case "ping":
        return {};
      case "tools/list":
        return { tools: TOOLS };
      case "tools/call":
        return await callTool(p);
      default:
        throw new RpcError(METHOD_NOT_FOUND, `Method not found: ${method}`);
    }
  }

  /** Handles one message; returns null when no response is due. */
  async function handleMessage(msg: unknown): Promise<JsonRpcResponse | null> {
    if (!isObject(msg) || msg.jsonrpc !== "2.0") {
      return rpcError(isObject(msg) && validId(msg.id) ? msg.id : null, INVALID_REQUEST, "Invalid Request");
    }
    const hasId = "id" in msg;
    if (typeof msg.method !== "string") {
      // A response from the client (result/error) needs no reply.
      if (hasId && ("result" in msg || "error" in msg)) return null;
      return rpcError(validId(msg.id) ? msg.id : null, INVALID_REQUEST, "Invalid Request");
    }
    if (!hasId) {
      // Notification (e.g. notifications/initialized, notifications/cancelled): no reply.
      return null;
    }
    if (!validId(msg.id)) return rpcError(null, INVALID_REQUEST, "Invalid Request: bad id");
    const id = msg.id;
    try {
      return { jsonrpc: "2.0", id, result: await dispatch(msg.method, msg.params) };
    } catch (e) {
      if (e instanceof RpcError) return rpcError(id, e.code, e.message);
      console.error(`method ${msg.method} failed:`, e instanceof Error ? e.message : String(e));
      return rpcError(id, INTERNAL_ERROR, "Internal error");
    }
  }

  return async function handle(req: Request): Promise<Response> {
    if (configError) {
      console.error(configError);
      return json({ error: "server misconfigured" }, 500);
    }

    const auth = req.headers.get("authorization") ?? "";
    const m = /^Bearer\s+(.+)$/i.exec(auth.trim());
    if (!m || !(await timingSafeEqual(m[1].trim(), mcpToken!))) {
      return json({ error: "unauthorized" }, 401, { "WWW-Authenticate": "Bearer" });
    }

    if (req.method !== "POST") {
      return new Response(null, { status: 405, headers: { Allow: "POST" } });
    }

    // MCP-Protocol-Version is accepted leniently: any value (or none) is fine.

    const raw = await req.text();
    if (encoder.encode(raw).length > MAX_BODY_BYTES) {
      return json(rpcError(null, INVALID_REQUEST, "Request too large"), 413);
    }
    let body: unknown;
    try {
      body = JSON.parse(raw);
    } catch {
      return json(rpcError(null, PARSE_ERROR, "Parse error"), 400);
    }

    if (Array.isArray(body)) {
      if (body.length === 0) {
        return json(rpcError(null, INVALID_REQUEST, "Invalid Request: empty batch"), 400);
      }
      const responses = (await Promise.all(body.map(handleMessage))).filter(
        (r): r is JsonRpcResponse => r !== null,
      );
      return responses.length ? json(responses) : new Response(null, { status: 202 });
    }

    const response = await handleMessage(body);
    return response ? json(response) : new Response(null, { status: 202 });
  };
}
