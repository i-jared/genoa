import { DurableObject } from "cloudflare:workers";
import type { Env } from "./types";
export class ConversationRoom extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/event") {
      const event = await request.text();
      for (const ws of this.ctx.getWebSockets())
        try {
          ws.send(event);
        } catch {
          ws.close();
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
      for (const client of this.ctx.getWebSockets())
        if (client !== ws)
          client.send(JSON.stringify({ type: message.type, userId }));
    }
  }
  webSocketClose(ws: WebSocket, code: number, reason: string) {
    ws.close(code, reason);
  }
}
