// Lingo Room guest relay.
// Passes messages between the host phone (the app) and the browser of a guest, and serves the guest page
// over https so that the browser allows the microphone. It keeps no conversation data: it only forwards.
import page from "./guest_page.html";

const MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
const MAX_GUESTS = 2;
const ROOM_LIFETIME_MS = 2 * 60 * 60 * 1000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const match = url.pathname.match(/^\/r\/([a-f0-9]{24,64})(\/ws)?$/);
    if (!match) {
      return new Response("Lingo Room guest relay", { status: url.pathname === "/" ? 200 : 404 });
    }
    if (!match[2]) {
      return new Response(page, {
        headers: {
          "content-type": "text/html; charset=utf-8",
          "cache-control": "no-store",
          "x-content-type-options": "nosniff",
          "referrer-policy": "no-referrer",
          "permissions-policy": "microphone=(self)",
        },
      });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    return env.ROOMS.get(env.ROOMS.idFromName(match[1])).fetch(request);
  },
};

export class Room {
  constructor(ctx) {
    this.ctx = ctx;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get("role") === "host" ? "host" : "guest";
    const hostKey = await this.ctx.storage.get("hostKey");
    if (role === "host") {
      // the first host to arrive owns the room, nobody else can take its place
      const key = url.searchParams.get("key") || "";
      if (key.length < 24 || (hostKey && hostKey !== key)) {
        return new Response("forbidden", { status: 403 });
      }
      if (!hostKey) {
        await this.ctx.storage.put("hostKey", key);
        await this.ctx.storage.setAlarm(Date.now() + ROOM_LIFETIME_MS);
      }
      for (const old of this.ctx.getWebSockets("host")) {
        try { old.close(1000, "replaced"); } catch (error) {}
      }
    } else {
      if (!hostKey) {
        return new Response("no such room", { status: 404 });
      }
      if (this.ctx.getWebSockets("guest").length >= MAX_GUESTS) {
        return new Response("room is full", { status: 429 });
      }
    }
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [role]);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  webSocketMessage(socket, message) {
    const size = typeof message === "string" ? message.length : message.byteLength;
    if (size > MAX_MESSAGE_BYTES) {
      return;
    }
    const fromHost = this.ctx.getTags(socket).includes("host");
    for (const target of this.ctx.getWebSockets(fromHost ? "guest" : "host")) {
      try { target.send(message); } catch (error) {}
    }
  }

  webSocketClose(socket, code) {
    try { socket.close(code === 1005 || code === 1006 ? 1000 : code, "closed"); } catch (error) {}
  }

  async alarm() {
    for (const socket of this.ctx.getWebSockets()) {
      try { socket.close(1000, "room ended"); } catch (error) {}
    }
    await this.ctx.storage.deleteAll();
  }
}
