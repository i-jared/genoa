import { DurableObject } from "cloudflare:workers";
import type { Env } from "./types";
export class ConversationRoom extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/event") {
      const event = await request.text();
      const sockets = this.ctx.getWebSockets();
      if (!sockets.length) return new Response("ok");
      // Revocation can be missed (e.g. a failed revoke call), so every broadcast rechecks membership.
      const { cid } = sockets[0]!.deserializeAttachment() as { cid: string };
      const members = await this.env.DB.prepare(
        "SELECT user_id FROM members WHERE conversation_id=? AND NOT EXISTS(SELECT 1 FROM suspensions s WHERE s.user_id=members.user_id)",
      )
        .bind(cid)
        .all<{ user_id: string }>();
      const allowed = new Set(members.results.map((m) => m.user_id));
      for (const ws of sockets) {
        const { userId } = ws.deserializeAttachment() as { userId: string };
        if (!allowed.has(userId)) {
          ws.close(1008, "Membership ended");
          continue;
        }
        try {
          ws.send(event);
        } catch {
          ws.close();
        }
      }
      return new Response("ok");
    }
    if (path === "/revoke") {
      const { userId } = await request.json<{ userId: string }>();
      for (const ws of this.ctx.getWebSockets(userId))
        ws.close(1008, "Membership ended");
      return new Response("ok");
    }
    if (request.headers.get("Upgrade") !== "websocket")
      return new Response("WebSocket required", { status: 426 });
    const userId = request.headers.get("X-User-Id"),
      cid = request.headers.get("X-Conversation-Id");
    if (!userId || !cid) return new Response("Unauthorized", { status: 401 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [userId]);
    pair[1].serializeAttachment({ userId, cid });
    return new Response(null, { status: 101, webSocket: pair[0] });
  }
  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer) {
    if (typeof data !== "string" || data.length > 1024) {
      ws.close(1009, "Too large");
      return;
    }
    const { userId, cid } = ws.deserializeAttachment() as {
      userId: string;
      cid: string;
    };
    const member = await this.env.DB.prepare(
      "SELECT 1 FROM members WHERE conversation_id=? AND user_id=?",
    )
      .bind(cid, userId)
      .first();
    if (!member) {
      ws.close(1008, "Membership ended");
      return;
    }
    let message: unknown;
    try {
      message = JSON.parse(data);
    } catch {
      return;
    }
    if (
      typeof message === "object" &&
      message !== null &&
      "type" in message &&
      ["recording", "stopped", "ping"].includes(String(message.type))
    ) {
      if (message.type === "ping") {
        ws.send('{"type":"pong"}');
        return;
      }
      const members = await this.env.DB.prepare(
        "SELECT user_id FROM members WHERE conversation_id=?",
      )
        .bind(cid)
        .all<{ user_id: string }>();
      const allowed = new Set(members.results.map((m) => m.user_id));
      for (const client of this.ctx.getWebSockets()) {
        if (client === ws) continue;
        const peer = (client.deserializeAttachment() as { userId: string }).userId;
        if (!allowed.has(peer)) client.close(1008, "Membership ended");
        else client.send(JSON.stringify({ type: message.type, userId }));
      }
    }
  }
  webSocketClose(ws: WebSocket, code: number, reason: string) {
    // 1005/1006 describe a missing or abnormal close and can't be sent back.
    ws.close(code === 1005 || code === 1006 ? 1000 : code, reason);
  }
}
