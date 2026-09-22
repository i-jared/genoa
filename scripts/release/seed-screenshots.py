#!/usr/bin/env python3
"""Seed fictional store content on the dedicated loopback Worker only."""
import json
from pathlib import Path
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://localhost:18888"
PASSWORD = "local-store-demo-password-123"


def request(method, path, body=None, token=None):
    headers = {"Origin": BASE, "X-Invite-Code": "local-genoa-beta"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if isinstance(body, bytes):
        data = body
        headers["Content-Type"] = "application/octet-stream"
    else:
        data = json.dumps(body).encode() if body is not None else None
        headers["Content-Type"] = "application/json"
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, headers=headers, method=method), timeout=30) as response:
        return json.load(response), response.headers


def account(email, name):
    _, headers = request("POST", "/auth/sign-up/email", {"email": email, "name": name, "password": PASSWORD})
    return headers["set-auth-token"]


def main():
    alex = account("alex@store.example.test", "Alex Morgan")
    maya = account("maya@store.example.test", "Maya Morgan")
    sam = account("sam@store.example.test", "Sam Morgan")
    for title, message in [
        ("Weekend plans", "Saturday at the lake? I'll bring the picnic."),
        ("Mom & Dad", "Loved hearing about your garden. Show us what's growing!"),
        ("Sam", "That story made my day. Let's catch up soon."),
        ("Family", "A little hello from the trail. Wish you were here!"),
    ]:
        cid = str(uuid.uuid4())
        request("POST", "/api/conversations", {"id": cid, "title": title}, alex)
        invitation, _ = request("POST", f"/api/conversations/{cid}/invite", {}, alex)
        request("POST", "/api/join", {"token": invitation["token"]}, maya)
        if title == "Family":
            request("POST", "/api/join", {"token": invitation["token"]}, sam)
            request("PATCH", f"/api/conversations/{cid}", {"favorite": True}, alex)
            request("PUT", f"/api/conversations/{cid}/note", {"text": "Ask Maya about the trail.\n\nTell everyone about our weekend plans.\n\nRemember to send Mom the picnic photos."}, alex)
        request("POST", f"/api/conversations/{cid}/messages", {"id": str(uuid.uuid4()), "text": message}, maya)
        if title == "Family":
            mid = str(uuid.uuid4())
            photo = (ROOT / "store/demo/trail-photo.png").read_bytes()
            request("POST", "/api/uploads", {"id": mid, "conversation_id": cid, "kind": "photo", "mime": "image/png", "bytes": len(photo), "duration_ms": 0}, maya)
            request("PUT", f"/api/uploads/{mid}/parts/1", photo, maya)
            request("POST", f"/api/uploads/{mid}/complete", {}, maya)
            # Open the latest photo instead of the preceding note on both clients.
            page, _ = request("GET", f"/api/conversations/{cid}/watch", token=alex)
            for item in page["messages"]:
                if item["id"] != mid:
                    request("PUT", f"/api/messages/{item['id']}/progress", {"position_ms": 0, "watched": True}, alex)
    print("Seeded four fictional conversations on localhost:18888. No production data used.")


if __name__ == "__main__":
    main()
