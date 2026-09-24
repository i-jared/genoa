import { test } from "node:test";
import assert from "node:assert/strict";
import { openSession, sealSession } from "../lib/session-crypto.ts";

const secret = "a".repeat(40);
const otherSecret = "b".repeat(40);
const future = () => Date.now() + 60_000;

test("round trips a sealed session", async () => {
  const exp = future();
  const sealed = await sealSession({ token: "tok_123", exp }, secret);
  assert.doesNotMatch(sealed, /tok_123/, "token must not appear in the cookie value");
  assert.deepEqual(await openSession(sealed, secret), { token: "tok_123", exp });
});

test("uses a fresh IV so equal payloads produce different cookies", async () => {
  const payload = { token: "tok", exp: future() };
  assert.notEqual(await sealSession(payload, secret), await sealSession(payload, secret));
});

test("rejects a value with any byte flipped", async () => {
  const sealed = await sealSession({ token: "tok", exp: future() }, secret);
  const [version, body] = sealed.split(".");
  for (const index of [0, 5, 12, body.length - 1]) {
    const flipped = body[index] === "A" ? "B" : "A";
    const tampered = `${version}.${body.slice(0, index)}${flipped}${body.slice(index + 1)}`;
    assert.equal(await openSession(tampered, secret), null, `tampered at ${index}`);
  }
});

test("rejects truncated, extended, and garbage values", async () => {
  const sealed = await sealSession({ token: "tok", exp: future() }, secret);
  for (const value of [sealed.slice(0, -4), `${sealed}AAAA`, `${sealed}.x`, "v2" + sealed.slice(2), "", "v1.", "v1.!!!", undefined]) {
    assert.equal(await openSession(value, secret), null);
  }
});

test("rejects a value sealed with a different secret", async () => {
  const sealed = await sealSession({ token: "tok", exp: future() }, otherSecret);
  assert.equal(await openSession(sealed, secret), null);
});

test("rejects an expired session", async () => {
  const exp = Date.now() + 1_000;
  const sealed = await sealSession({ token: "tok", exp }, secret);
  assert.equal(await openSession(sealed, secret, exp), null);
  assert.equal(await openSession(sealed, secret, exp + 1), null);
  assert.ok(await openSession(sealed, secret, exp - 1));
});
