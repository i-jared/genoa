function signupPeople(...names) {
  return names.map((name) => ({
    name,
    command: { tag: "signup", as: name },
    bind: { [`${name}Id`]: "body.id" },
    assert: [{ kind: "status", equals: 200 }],
  }));
}

function mintWeekend() {
  return [
    { name: "cid", command: { tag: "uuid", bind: "cid" }, assert: [] },
    {
      name: "create",
      command: {
        tag: "api",
        method: "POST",
        path: "/api/conversations",
        as: "alice",
        body: { id: "$cid", title: "Weekend plans" },
      },
      assert: [
        { kind: "status", equals: 201 },
        { kind: "json-eq", path: "body.id", equals: "$cid" },
      ],
    },
  ];
}

function inviteAndJoinBob() {
  return [
    {
      name: "invite",
      command: { tag: "api", method: "POST", path: "/api/conversations/$cid/invite", as: "alice", body: {} },
      bind: { token: "body.token" },
      assert: [{ kind: "status", equals: 200 }],
    },
    {
      name: "join",
      command: { tag: "api", method: "POST", path: "/api/join", as: "bob", body: { token: "$token" } },
      assert: [{ kind: "status", equals: 200 }],
    },
  ];
}

function pairWithWeekend() {
  return [...signupPeople("alice", "bob"), ...mintWeekend(), ...inviteAndJoinBob()];
}

function trioWithWeekend() {
  return [...signupPeople("alice", "bob", "eve"), ...mintWeekend(), ...inviteAndJoinBob()];
}

function mintMessage(name, as, text, extra = {}) {
  return [
    { name, command: { tag: "uuid", bind: name }, assert: [] },
    {
      name: `send-${name}`,
      command: {
        tag: "api",
        method: "POST",
        path: "/api/conversations/$cid/messages",
        as,
        body: { id: `$${name}`, text, ...extra },
      },
      assert: [{ kind: "status", equals: 201 }, { kind: "json-eq", path: "body.id", equals: `$${name}` }],
    },
  ];
}

const JPEG_BYTES = 123;

/** @type {Record<string, object>} */
export const REGISTRY = {
  account: {
    id: "account",
    entry: "http",
    claims: [{ actions: ["register"], observations: ["session", "closed-list"] }],
    steps: [
      {
        name: "register",
        command: { tag: "signup", as: "alice" },
        bind: { aliceId: "body.id" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "json-eq", path: "body.name", equals: "alice" },
        ],
      },
      {
        name: "session",
        command: { tag: "api", method: "GET", path: "/api/me", as: "alice" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "json-eq", path: "body.name", equals: "alice" },
        ],
      },
      {
        name: "closed-list",
        command: { tag: "unauth", method: "GET", path: "/api/conversations" },
        assert: [{ kind: "status", equals: 401 }],
      },
      {
        name: "bad-invite",
        command: { tag: "signup", as: "interloper", invite: "wrong" },
        assert: [{ kind: "status", equals: 403 }],
      },
      {
        name: "signin",
        command: { tag: "signin", as: "alice" },
        assert: [{ kind: "status", equals: 200 }],
      },
    ],
  },
  conversations: {
    id: "conversations",
    entry: "http",
    claims: [
      { actions: ["create"], observations: ["list-owner"] },
      { actions: ["invite"], observations: ["hidden"] },
      { actions: ["join"], observations: ["list-member"] },
    ],
    steps: [
      ...signupPeople("alice", "bob", "eve"),
      ...mintWeekend(),
      {
        name: "list-owner",
        command: { tag: "api", method: "GET", path: "/api/conversations", as: "alice" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { title: "Weekend plans" }, equals: 1 },
        ],
      },
      {
        name: "invite",
        command: { tag: "api", method: "POST", path: "/api/conversations/$cid/invite", as: "alice", body: {} },
        bind: { token: "body.token" },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "hidden",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid", as: "eve" },
        assert: [{ kind: "status", equals: 404 }],
      },
      {
        name: "join",
        command: { tag: "api", method: "POST", path: "/api/join", as: "bob", body: { token: "$token" } },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "list-member",
        command: { tag: "api", method: "GET", path: "/api/conversations", as: "bob" },
        assert: [{ kind: "count", of: "body", match: { title: "Weekend plans" }, equals: 1 }],
      },
    ],
  },
  "send-text": {
    id: "send-text",
    entry: "http",
    claims: [{ actions: ["send"], observations: ["recipient"] }],
    steps: [
      ...pairWithWeekend(),
      { name: "mid", command: { tag: "uuid", bind: "mid" }, assert: [] },
      {
        name: "send",
        command: {
          tag: "api",
          method: "POST",
          path: "/api/conversations/$cid/messages",
          as: "alice",
          body: { id: "$mid", text: "See you Saturday" },
        },
        assert: [
          { kind: "status", equals: 201 },
          { kind: "json-eq", path: "body.id", equals: "$mid" },
        ],
      },
      {
        name: "retry",
        command: {
          tag: "api",
          method: "POST",
          path: "/api/conversations/$cid/messages",
          as: "alice",
          body: { id: "$mid", text: "See you Saturday" },
        },
        assert: [{ kind: "status", equals: 201 }],
      },
      {
        name: "recipient",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          {
            kind: "count",
            of: "body",
            match: { text: "See you Saturday", kind: "text", state: "ready" },
            equals: 1,
          },
        ],
      },
    ],
  },
  notes: {
    id: "notes",
    entry: "http",
    claims: [{ actions: ["write"], observations: ["alice-note", "bob-note"] }],
    steps: [
      ...pairWithWeekend(),
      {
        name: "write",
        command: {
          tag: "api",
          method: "PUT",
          path: "/api/conversations/$cid/note",
          as: "alice",
          body: { text: "Bring a jacket" },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "alice-note",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid", as: "alice" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "json-eq", path: "body.note", equals: "Bring a jacket" },
        ],
      },
      {
        name: "bob-note",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "json-eq", path: "body.note", equals: "" },
        ],
      },
    ],
  },
  reactions: {
    id: "reactions",
    entry: "http",
    claims: [{ actions: ["react"], observations: ["bob-sees"] }],
    steps: [
      ...pairWithWeekend(),
      ...mintMessage("mid", "alice", "See you Saturday"),
      {
        name: "react",
        command: {
          tag: "api",
          method: "PUT",
          path: "/api/messages/$mid/reaction",
          as: "alice",
          body: { emoji: "👍" },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "bob-sees",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "json-eq", path: "body.0.reactions.0.emoji", equals: "👍" },
        ],
      },
    ],
  },
  bookmarks: {
    id: "bookmarks",
    entry: "http",
    claims: [{ actions: ["mark"], observations: ["alice-list", "bob-list"] }],
    steps: [
      ...pairWithWeekend(),
      ...mintMessage("mid", "alice", "See you Saturday"),
      {
        name: "mark",
        command: {
          tag: "api",
          method: "PUT",
          path: "/api/messages/$mid/bookmark",
          as: "alice",
          body: { enabled: true },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "alice-list",
        command: {
          tag: "api",
          method: "GET",
          path: "/api/conversations/$cid/messages?bookmarked=true",
          as: "alice",
        },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { text: "See you Saturday" }, equals: 1 },
        ],
      },
      {
        name: "bob-list",
        command: {
          tag: "api",
          method: "GET",
          path: "/api/conversations/$cid/messages?bookmarked=true",
          as: "bob",
        },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { text: "See you Saturday" }, equals: 0 },
        ],
      },
    ],
  },
  progress: {
    id: "progress",
    entry: "http",
    claims: [{ actions: ["watch"], observations: ["bob-unwatched"] }],
    steps: [
      ...pairWithWeekend(),
      ...mintMessage("mid", "alice", "See you Saturday"),
      {
        name: "watch",
        command: {
          tag: "api",
          method: "PUT",
          path: "/api/messages/$mid/progress",
          as: "alice",
          body: { position_ms: 1000, watched: true },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "bob-unwatched",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          {
            kind: "count",
            of: "body",
            match: { text: "See you Saturday", watched: "0" },
            equals: 1,
          },
        ],
      },
    ],
  },
  rename: {
    id: "rename",
    entry: "http",
    claims: [{ actions: ["patch-title"], observations: ["bob-list", "eve-hidden"] }],
    steps: [
      ...trioWithWeekend(),
      {
        name: "patch-title",
        command: {
          tag: "api",
          method: "PATCH",
          path: "/api/conversations/$cid",
          as: "alice",
          body: { title: "Saturday hike" },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "bob-list",
        command: { tag: "api", method: "GET", path: "/api/conversations", as: "bob" },
        assert: [{ kind: "count", of: "body", match: { title: "Saturday hike" }, equals: 1 }],
      },
      {
        name: "eve-hidden",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid", as: "eve" },
        assert: [{ kind: "status", equals: 404 }],
      },
    ],
  },
  leave: {
    id: "leave",
    entry: "http",
    claims: [{ actions: ["bob-leave"], observations: ["bob-gone", "alice-members"] }],
    steps: [
      ...pairWithWeekend(),
      {
        name: "bob-leave",
        command: { tag: "api", method: "DELETE", path: "/api/conversations/$cid/members/$bobId", as: "bob" },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "bob-gone",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid", as: "bob" },
        assert: [{ kind: "status", equals: 404 }],
      },
      {
        name: "alice-members",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid", as: "alice" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "json-eq", path: "body.members.length", equals: "1" },
        ],
      },
    ],
  },
  "mute-favorite": {
    id: "mute-favorite",
    entry: "http",
    claims: [{ actions: ["patch"], observations: ["list"] }],
    steps: [
      ...pairWithWeekend(),
      {
        name: "patch",
        command: {
          tag: "api",
          method: "PATCH",
          path: "/api/conversations/$cid",
          as: "alice",
          body: { muted: true, favorite: true },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "list",
        command: { tag: "api", method: "GET", path: "/api/conversations", as: "alice" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { title: "Weekend plans", muted: "1", favorite: "1" }, equals: 1 },
        ],
      },
    ],
  },
  urgent: {
    id: "urgent",
    entry: "http",
    claims: [{ actions: ["send-urgent"], observations: ["bob-sees"] }],
    steps: [
      ...pairWithWeekend(),
      { name: "mid", command: { tag: "uuid", bind: "mid" }, assert: [] },
      {
        name: "send-urgent",
        command: {
          tag: "api",
          method: "POST",
          path: "/api/conversations/$cid/messages",
          as: "alice",
          body: { id: "$mid", text: "Call me now", urgent: true },
        },
        assert: [{ kind: "status", equals: 201 }],
      },
      {
        name: "bob-sees",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { text: "Call me now", urgent: "1" }, equals: 1 },
        ],
      },
    ],
  },
  "reply-to": {
    id: "reply-to",
    entry: "http",
    claims: [{ actions: ["reply"], observations: ["bob-sees"] }],
    steps: [
      ...pairWithWeekend(),
      ...mintMessage("mid", "alice", "See you Saturday"),
      { name: "rid", command: { tag: "uuid", bind: "rid" }, assert: [] },
      {
        name: "reply",
        command: {
          tag: "api",
          method: "POST",
          path: "/api/conversations/$cid/messages",
          as: "alice",
          body: { id: "$rid", text: "Bring snacks", reply_to: "$mid" },
        },
        assert: [{ kind: "status", equals: 201 }],
      },
      {
        name: "bob-sees",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { text: "Bring snacks", reply_to: "$mid" }, equals: 1 },
        ],
      },
    ],
  },
  pagination: {
    id: "pagination",
    entry: "http",
    claims: [{ actions: ["send-older", "send-newer"], observations: ["page"] }],
    steps: [
      ...pairWithWeekend(),
      ...mintMessage("older", "alice", "First note"),
      ...mintMessage("newer", "alice", "Second note"),
      {
        name: "both",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        bind: { newerSeq: "body.1.seq" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: {}, equals: 2 },
        ],
      },
      {
        name: "page",
        command: {
          tag: "api",
          method: "GET",
          path: "/api/conversations/$cid/messages?before=$newerSeq",
          as: "bob",
        },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { text: "First note" }, equals: 1 },
        ],
      },
    ],
  },
  "member-remove": {
    id: "member-remove",
    entry: "http",
    claims: [{ actions: ["remove"], observations: ["bob-gone"] }],
    steps: [
      ...pairWithWeekend(),
      {
        name: "remove",
        command: { tag: "api", method: "DELETE", path: "/api/conversations/$cid/members/$bobId", as: "alice" },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "bob-gone",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid", as: "bob" },
        assert: [{ kind: "status", equals: 404 }],
      },
    ],
  },
  "upload-resume": {
    id: "upload-resume",
    entry: "http",
    claims: [
      { actions: ["open", "part"], observations: ["status"] },
      { actions: ["complete"], observations: ["range"] },
    ],
    steps: [
      ...pairWithWeekend(),
      { name: "mid", command: { tag: "uuid", bind: "mid" }, assert: [] },
      {
        name: "open",
        command: {
          tag: "api",
          method: "POST",
          path: "/api/uploads",
          as: "alice",
          body: {
            id: "$mid",
            conversation_id: "$cid",
            kind: "photo",
            mime: "image/jpeg",
            bytes: JPEG_BYTES,
            duration_ms: 0,
          },
        },
        assert: [{ kind: "status", equals: 201 }],
      },
      {
        name: "part",
        command: {
          tag: "put-bytes",
          path: "/api/uploads/$mid/parts/1",
          as: "alice",
          source: { kind: "fixture", id: "jpeg" },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "status",
        command: { tag: "api", method: "GET", path: "/api/uploads/$mid", as: "alice" },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "complete",
        command: { tag: "api", method: "POST", path: "/api/uploads/$mid/complete", as: "alice", body: {} },
        assert: [{ kind: "json-eq", path: "body.state", equals: "ready" }],
      },
      {
        name: "range",
        command: { tag: "range-get", path: "/api/media/$mid", as: "bob", range: "bytes=0-1" },
        assert: [{ kind: "status", equals: 206 }],
      },
    ],
  },
  live: {
    id: "live",
    entry: "http",
    claims: [{ actions: ["start", "seg0"], observations: ["playlist"] }],
    steps: [
      ...pairWithWeekend(),
      { name: "mid", command: { tag: "uuid", bind: "mid" }, assert: [] },
      {
        name: "start",
        command: {
          tag: "api",
          method: "POST",
          path: "/api/conversations/$cid/live",
          as: "alice",
          body: { id: "$mid", rotation: 0 },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "seg0",
        command: {
          tag: "put-bytes",
          path: "/api/live/$mid/segments/0?duration=2",
          as: "alice",
          source: { kind: "fixture", id: "ts" },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "playlist",
        command: { tag: "api", method: "GET", path: "/api/live/$mid/playlist.m3u8", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "includes", path: "body.text", needle: "segments/0.ts" },
        ],
      },
      {
        name: "end",
        command: { tag: "api", method: "POST", path: "/api/live/$mid/end", as: "alice", body: {} },
        assert: [{ kind: "status", equals: 200 }],
      },
    ],
  },
  "account-export-delete": {
    id: "account-export-delete",
    entry: "http",
    claims: [{ actions: ["delete"], observations: ["revoked"] }],
    steps: [
      ...signupPeople("alice"),
      {
        name: "export",
        command: { tag: "api", method: "GET", path: "/api/account/export", as: "alice" },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "delete",
        command: {
          tag: "api",
          method: "POST",
          path: "/api/account/delete",
          as: "alice",
          body: { confirmation: "DELETE" },
        },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "revoked",
        command: { tag: "api", method: "GET", path: "/api/me", as: "alice" },
        assert: [{ kind: "status", equals: 401 }],
      },
    ],
  },
  blocks: {
    id: "blocks",
    entry: "http",
    claims: [{ actions: ["block"], observations: ["hidden"] }],
    steps: [
      ...pairWithWeekend(),
      ...mintMessage("mid", "bob", "from bob"),
      {
        name: "block",
        command: { tag: "api", method: "POST", path: "/api/blocks", as: "alice", body: { user_id: "$bobId" } },
        assert: [{ kind: "status", equals: 200 }],
      },
      {
        name: "hidden",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "alice" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { text: "from bob" }, equals: 0 },
        ],
      },
    ],
  },
  search: {
    id: "search",
    entry: "android",
    claims: [{ actions: ["type"], observations: ["wait-empty", "empty-dump"] }],
    steps: [
      { name: "gate", command: { tag: "android-doctor" }, assert: [] },
      { name: "open", command: { tag: "android-tap-desc", value: "Search conversations" }, assert: [] },
      { name: "type", command: { tag: "android-type", text: "missing conversation" }, assert: [] },
      {
        name: "wait-empty",
        command: {
          tag: "wait",
          spec: { kind: "until-text", text: "No conversations match ‘missing conversation’.", timeoutMs: 8000 },
        },
        assert: [],
      },
      {
        name: "empty-dump",
        command: { tag: "android-dump", name: "empty.xml" },
        assert: [
          {
            kind: "count",
            of: "texts",
            match: { text: "No conversations match ‘missing conversation’." },
            equals: 1,
          },
        ],
      },
      { name: "clear", command: { tag: "android-tap-desc", value: "Close search" }, assert: [] },
      { name: "shot", command: { tag: "android-screenshot", name: "empty.png" }, assert: [] },
    ],
  },
  record: {
    id: "record",
    entry: "android",
    claims: [{ actions: ["keep"], observations: ["bob-empty"] }],
    steps: [
      { name: "gate", command: { tag: "android-doctor" }, assert: [] },
      ...pairWithWeekend(),
      { name: "open-record", command: { tag: "android-tap-desc", value: "Record" }, assert: [] },
      { name: "pick", command: { tag: "android-tap-text", value: "Weekend plans" }, assert: [] },
      {
        name: "wait-start",
        command: { tag: "wait", spec: { kind: "until-desc", desc: "Start recording", timeoutMs: 10000 } },
        assert: [],
      },
      { name: "start", command: { tag: "android-tap-desc", value: "Start recording" }, assert: [] },
      {
        name: "wait-stop",
        command: { tag: "wait", spec: { kind: "until-desc", desc: "Stop recording", timeoutMs: 20000 } },
        assert: [],
      },
      { name: "hold", command: { tag: "wait", spec: { kind: "ms", ms: 2500 } }, assert: [] },
      { name: "stop", command: { tag: "android-tap-desc", value: "Stop recording" }, assert: [] },
      {
        name: "wait-saved",
        command: { tag: "wait", spec: { kind: "until-text", text: "Saved on this phone", timeoutMs: 15000 } },
        assert: [],
      },
      { name: "review-dump", command: { tag: "android-dump", name: "review.xml" }, assert: [] },
      { name: "review-shot", command: { tag: "android-screenshot", name: "review.png" }, assert: [] },
      { name: "keep", command: { tag: "android-tap-text", value: "Keep draft" }, assert: [] },
      {
        name: "bob-empty",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          { kind: "count", of: "body", match: { kind: "video", state: "ready" }, equals: 0 },
          { kind: "count", of: "body", match: { kind: "audio", state: "ready" }, equals: 0 },
        ],
      },
    ],
  },
  "send-text-android": {
    id: "send-text-android",
    entry: "android",
    claims: [{ actions: ["send-note"], observations: ["recipient"] }],
    steps: [
      { name: "gate", command: { tag: "android-doctor" }, assert: [] },
      ...pairWithWeekend(),
      { name: "open-thread", command: { tag: "android-tap-text", value: "Weekend plans" }, assert: [] },
      { name: "reply-options", command: { tag: "android-tap-desc", value: "Reply options" }, assert: [] },
      { name: "write", command: { tag: "android-tap-text", value: "Write a text message" }, assert: [] },
      { name: "type", command: { tag: "android-type", text: "See you Saturday" }, assert: [] },
      { name: "send-note", command: { tag: "android-tap-desc", value: "Send note" }, assert: [] },
      {
        name: "wait-sent",
        command: { tag: "wait", spec: { kind: "ms", ms: 2000 } },
        assert: [],
      },
      {
        name: "recipient",
        command: { tag: "api", method: "GET", path: "/api/conversations/$cid/messages", as: "bob" },
        assert: [
          { kind: "status", equals: 200 },
          {
            kind: "count",
            of: "body",
            match: { text: "See you Saturday", kind: "text", state: "ready" },
            equals: 1,
          },
        ],
      },
    ],
  },
  "verify-ai": {
    id: "verify-ai",
    entry: "out-of-harness",
    reason: "backend/tools/verify-ai.mjs hits paid Workers AI; isolated env has no AI binding",
  },
  fcm: {
    id: "fcm",
    entry: "out-of-harness",
    reason: "isolated env has no FCM_SERVICE_ACCOUNT; delivery is not locally observable",
  },
  ffprobe: {
    id: "ffprobe",
    entry: "out-of-harness",
    reason: "codec inspection is not the phone HTTP contract",
  },
  "physical-device": {
    id: "physical-device",
    entry: "out-of-harness",
    reason: "harness refuses non-emulator serials",
  },
  "genoa-flow-test": {
    id: "genoa-flow-test",
    entry: "out-of-harness",
    reason: "GenoaFlowTest defaults to 10.0.2.2:8787; not this harness unless retargeted to 18787",
  },
};
