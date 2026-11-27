// Lingo Room guest relay.
// Passes messages between the host phone (the app) and the browsers of the guests, and serves the guest page
// over https so that browsers allow the microphone. It keeps no conversation data: it only forwards.
// A room is reached at /<name>, where the name is chosen by its host, and guests need the PIN of the room.
import page from "./guest_page.html";

const MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
const MAX_GUESTS = 25;
const ROOM_LIFETIME_MS = 6 * 60 * 60 * 1000;
const MAX_WRONG_PINS = 8;
const WRONG_PIN_WINDOW_MS = 10 * 60 * 1000;

function json(value, status) {
  return new Response(JSON.stringify(value), {
    status: status || 200,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // /<name> for rooms named by their host, /r/<long code> for older versions of the app
    const match = url.pathname.match(/^\/(?:r\/([a-f0-9]{24,64})|([a-z0-9][a-z0-9-]{2,23}))(\/ws|\/check|\/owner)?$/);
    if (!match) {
      return new Response("Lingo Room guest relay", { status: url.pathname === "/" ? 200 : 404 });
    }
    const room = match[1] || match[2];
    const action = match[3];
    if (!action) {
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
    if (action === "/ws" && request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(request);
  },
};

export class Room {
  constructor(ctx) {
    this.ctx = ctx;
  }

  // true if the PIN is right (or the room has none). Too many wrong tries close the door for a while.
  async pinState(given) {
    const pin = await this.ctx.storage.get("pin");
    if (!pin) {
      return "ok";
    }
    const now = Date.now();
    const wrong = ((await this.ctx.storage.get("wrong")) || []).filter((time) => now - time < WRONG_PIN_WINDOW_MS);
    if (wrong.length >= MAX_WRONG_PINS) {
      return "locked";
    }
    if (given === pin) {
      return "ok";
    }
    wrong.push(now);
    await this.ctx.storage.put("wrong", wrong);
    return "wrong";
  }

  async fetch(request) {
    const url = new URL(request.url);
    const hostKey = await this.ctx.storage.get("hostKey");
    if (url.pathname.endsWith("/owner")) {
      // the app asks whether a room name is free before it takes it
      const key = url.searchParams.get("key") || "";
      return json({ state: !hostKey ? "free" : hostKey === key ? "mine" : "taken" });
    }
    if (url.pathname.endsWith("/check")) {
      // the guest page asks whether the PIN is right before it connects
      if (!hostKey) {
        return json({ state: "none" }, 404);
      }
      const state = await this.pinState(url.searchParams.get("pin") || "");
      return json({ state: state }, state === "ok" ? 200 : state === "locked" ? 429 : 403);
    }
    const role = url.searchParams.get("role") === "host" ? "host" : "guest";
    if (role === "host") {
      // the first host to arrive owns the room, nobody else can take its place
      const key = url.searchParams.get("key") || "";
      if (key.length < 24 || (hostKey && hostKey !== key)) {
        return new Response("forbidden", { status: 403 });
      }
      if (!hostKey) {
        await this.ctx.storage.put("hostKey", key);
      }
      const pin = url.searchParams.get("pin") || "";
      if (/^[0-9]{4,8}$/.test(pin)) {
        await this.ctx.storage.put("pin", pin);
      }
      await this.ctx.storage.setAlarm(Date.now() + ROOM_LIFETIME_MS);
      for (const old of this.ctx.getWebSockets("host")) {
        try { old.close(1000, "replaced"); } catch (error) {}
      }
    } else {
      if (!hostKey) {
        return new Response("no such room", { status: 404 });
      }
      if ((await this.pinState(url.searchParams.get("pin") || "")) !== "ok") {
        return new Response("wrong pin", { status: 403 });
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
    // the room keeps its owner: a phone has one lasting link, and nobody else may take it over
  }
}
