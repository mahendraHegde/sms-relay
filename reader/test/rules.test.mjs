// Run: node --test reader/test/*.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as R from "../rules.js";

const vectors = JSON.parse(readFileSync(new URL("../../test-vectors/rules.json", import.meta.url), "utf8"));
const wire = (when, extra = {}) => ({ id: "r1", name: "One", enabled: true, when, forwardTo: ["+15555550100"], ...extra });

test("matching agrees with the shared vectors (the phone runs the same cases)", () => {
  for (const c of vectors.cases) {
    const model = R.fromWire(wire(c.when));
    assert.ok(model, JSON.stringify(c.when));
    assert.equal(R.matches(model, c.sender, c.body), c.match, JSON.stringify(c));
  }
});

test("editor model round-trips through the wire format", () => {
  for (const c of vectors.cases) {
    const model = R.fromWire(wire(c.when));
    assert.deepEqual(R.toWire(model).when, c.when);
  }
});

test("shapes the editor cannot show come back as null, not as a wrong rule", () => {
  assert.equal(R.fromWire(wire({ not: { any: [{ field: "body", op: "contains", value: "x" }] } })), null);
  assert.equal(R.fromWire(wire({ all: [{ any: [{ all: [{ field: "body", op: "contains", value: "x" }] }] }] })), null);
  assert.equal(R.fromWire(wire({ field: "title", op: "contains", value: "x" })), null);
  assert.equal(R.fromWire(wire({ not: { not: { field: "body", op: "startsWith", value: "x" } } })), null);
});

test("problems explain what blocks saving", () => {
  const r = R.blankRule();
  assert.ok(R.problems(r, []).some((p) => p.includes("type the text")));
  assert.ok(R.problems(r, []).some((p) => p.includes("at least one number")));
  assert.ok(R.problems(r, []).some((p) => p.includes("name")));
  r.name = "Bank codes";
  r.groups[0].conds[0].value = "EXBANK";
  r.forwardTo = ["+15555550100"];
  assert.deepEqual(R.problems(r, ["+15555550100"]), []);
  assert.ok(R.problems(r, []).some((p) => p.includes("Not approved")));
  r.groups[0].conds[0].op = "notContains";
  assert.ok(R.problems(r, ["+15555550100"]).some((p) => p.includes("almost every message")));
  r.groups[0].conds[0].op = "contains";
  r.groups = Array.from({ length: 11 }, () => ({ match: "all", conds: [{ field: "body", op: "contains", value: "a" }, { field: "body", op: "contains", value: "b" }] }));
  assert.ok(R.problems(r, ["+15555550100"]).some((p) => p.includes("Too many conditions")));
  const other = { ...R.blankRule(), name: "Codes" };
  assert.ok(R.problems({ ...r, name: "codes" }, ["+15555550100"], [other]).some((p) => p.includes("Another rule")));
});

test("whole-set size limit", () => {
  const big = { version: 1, rules: Array.from({ length: 50 }, (_, i) => wire({ any: Array.from({ length: 4 }, () => ({ field: "body", op: "contains", value: "x".repeat(200) })) }, { id: `r${i}` })) };
  assert.ok(R.setProblem(big));
  assert.equal(R.setProblem({ version: 1, rules: [wire({ field: "body", op: "contains", value: "x" })] }), null);
});

test("describe reads as plain English", () => {
  const m = R.fromWire(wire(vectors.cases[10].when));
  assert.equal(R.describe(m), '(the text contains "XXX" and the text contains "YYY"), or the sender is exactly "ZZZ"');
  const n = R.fromWire(wire(vectors.cases[14].when));
  assert.equal(n.match, "all");
  assert.equal(R.describe(n), 'the sender contains "EXBANK", and (the text contains "OTP" or the text contains "code")');
});

test("an OR group with one condition per group still round-trips to the same meaning", () => {
  const r = { ...R.blankRule(), match: "all", groups: [{ match: "all", conds: [{ field: "body", op: "contains", value: "a" }] }, { match: "all", conds: [{ field: "body", op: "contains", value: "b" }] }] };
  const back = R.fromWire(R.toWire(r));
  for (const [s, b] of [["", "ab"], ["", "a"], ["", "b"], ["", ""]]) assert.equal(R.matches(back, s, b), R.matches(r, s, b));
});

test("limits match the shared vectors (the phone checks the same file)", () => {
  assert.deepEqual(R.LIMITS, vectors.limits);
});

test("per-rule limits block saving", () => {
  const ok = ["+15555550100"];
  const base = () => ({ ...R.blankRule(), name: "N", forwardTo: [...ok], groups: [{ match: "all", conds: [{ field: "body", op: "contains", value: "x" }] }] });
  assert.deepEqual(R.problems(base(), ok), []);
  assert.ok(R.problems({ ...base(), name: "n".repeat(R.LIMITS.name + 1) }, ok).length);
  assert.deepEqual(R.problems({ ...base(), name: "n".repeat(R.LIMITS.name) }, ok), []);
  const long = base();
  long.groups[0].conds[0].value = "v".repeat(R.LIMITS.value + 1);
  assert.ok(R.problems(long, ok).length);
  long.groups[0].conds[0].value = "v".repeat(R.LIMITS.value);
  assert.deepEqual(R.problems(long, ok), []);
  const blank = base();
  blank.groups[0].conds[0].value = "   ";
  assert.ok(R.problems(blank, ok).some((p) => p.includes("type the text")));
  const many = Array.from({ length: R.LIMITS.destinations + 1 }, (_, i) => `+1555555020${i}`);
  assert.ok(R.problems({ ...base(), forwardTo: many }, many).length);
  assert.deepEqual(R.problems({ ...base(), forwardTo: many.slice(0, R.LIMITS.destinations) }, many), []);
  // A negated condition counts its "not" node: 10 negated conditions in one group = 21 nodes.
  const negs = base();
  negs.groups = [{ match: "all", conds: [{ field: "body", op: "contains", value: "x" }, ...Array.from({ length: 9 }, () => ({ field: "body", op: "notContains", value: "y" }))] }];
  assert.deepEqual(R.problems(negs, ok), []);
  negs.groups[0].conds.push({ field: "body", op: "notContains", value: "z" });
  assert.ok(R.problems(negs, ok).some((p) => p.includes("Too many conditions")));
  // ANY groups count too: 1 + 10 groups of (any + 2) = 31 nodes.
  const anys = base();
  anys.match = "all";
  anys.groups = Array.from({ length: 10 }, () => ({ match: "any", conds: [{ field: "body", op: "contains", value: "a" }, { field: "body", op: "contains", value: "b" }] }));
  assert.ok(R.problems(anys, ok).some((p) => p.includes("Too many conditions")));
  const ctrl = base();
  ctrl.groups[0].conds[0].value = "\u001c ";
  assert.ok(R.problems(ctrl, ok).some((p) => p.includes("type the text")));
});

test("rule count and size limits for the whole set", () => {
  const one = (i) => wire({ field: "body", op: "contains", value: "x" }, { id: `r${i}` });
  assert.equal(R.setProblem({ version: 1, rules: Array.from({ length: R.LIMITS.rules }, (_, i) => one(i)) }), null);
  assert.ok(R.setProblem({ version: 1, rules: Array.from({ length: R.LIMITS.rules + 1 }, (_, i) => one(i)) }));
  // "/" counts twice, as the phone's JSON library escapes it.
  const urls = (n) => ({ version: 1, rules: Array.from({ length: n }, (_, i) => wire({ any: Array.from({ length: 4 }, () => ({ field: "body", op: "contains", value: "/".repeat(150) })) }, { id: `r${i}` })) });
  assert.equal(R.setProblem(urls(22)), null); // 32.2 KB escaped
  assert.ok(R.setProblem(urls(25))); // 21.6 KB as JSON.stringify writes it, 36.6 KB escaped
});

test("toWire falls back to the id as name and fromWire keeps enabled", () => {
  const r = { ...R.blankRule(), name: "  " };
  assert.equal(R.toWire(r).name, r.id);
  assert.equal(R.fromWire(wire({ field: "body", op: "contains", value: "x" }, { enabled: false })).enabled, false);
  assert.equal(R.fromWire(wire({ field: "body", op: "contains", value: "x" })).enabled, true);
  const { enabled, ...noFlag } = wire({ field: "body", op: "contains", value: "x" });
  assert.equal(R.fromWire(noFlag).enabled, true);
});

test("nodes with more than one kind are not shown", () => {
  assert.equal(R.fromWire(wire({ not: { field: "body", op: "contains", value: "x" }, all: [{ field: "body", op: "contains", value: "y" }] })), null);
  assert.equal(R.fromWire(wire({ field: "body", op: "contains", value: "x", any: [] })), null);
  assert.equal(R.fromWire(wire({ not: { field: "body", op: "contains", value: "x", all: [] } })), null);
});

test("cleanAllow keeps only well-formed entries", () => {
  assert.equal(R.cleanAllow(undefined), null);
  assert.deepEqual(R.cleanAllow([{ n: "+15555550100", a: true, p: "ab12" }, { n: "x", a: true }, { n: "+15555550101", a: "yes" }, null, { n: "+15555550102", p: "<b>" }]), [
    { n: "+15555550100", a: true, p: "ab12" },
    { n: "+15555550101", a: false, p: "" },
  ]);
  assert.equal(R.cleanAllow(Array.from({ length: 30 }, (_, i) => ({ n: `+1555555${1000 + i}` }))).length, R.LIMITS.allowlist);
});
