import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { URL } from "node:url";

test("reaction migration preserves history and accepts legacy writes during cutover", () => {
  const db = new DatabaseSync(":memory:");
  try {
    for (const name of ["0001", "0002", "0003", "0004"]) db.exec(readFileSync(new URL(`../migrations/${name}.sql`, import.meta.url), "utf8"));
    db.exec("INSERT INTO user(id,name,email,createdAt,updatedAt) VALUES('u','Rosa','migration@example.test',0,0); INSERT INTO conversations VALUES('c','Test','u',0); INSERT INTO messages(id,conversation_id,author_id,kind,state,created_at) VALUES('m','c','u','text','ready',0); INSERT INTO reactions VALUES('m','u','❤️')");
    db.exec(readFileSync(new URL("../migrations/0005.sql", import.meta.url), "utf8"));
    assert.equal(db.prepare("SELECT emoji FROM reaction_moments").get()?.emoji, "❤️");
    db.exec("UPDATE reactions SET emoji='😂'");
    assert.equal(db.prepare("SELECT emoji FROM reaction_moments").get()?.emoji, "😂");
    db.exec(readFileSync(new URL("../migrations/0006.sql", import.meta.url), "utf8"));
    db.exec("DELETE FROM reactions");
    assert.equal(db.prepare("SELECT COUNT(*) n FROM reaction_moments").get()?.n, 0);
    db.exec("INSERT INTO reactions VALUES('m','u','🔥')");
    assert.equal(db.prepare("SELECT emoji FROM reaction_moments").get()?.emoji, "🔥");
  } finally { db.close(); }
});
