// Forwarding rules as the reader edits them. The phone is the authority: it re-validates every
// rule set and refuses anything outside its limits. The checks here only give the user a clear
// message before anything is sent (keep them in step with core/Rules.kt).

// test-vectors/rules.json holds the same numbers; both test suites check them.
export const LIMITS = { rules: 50, nodes: 20, value: 200, destinations: 5, name: 60, setBytes: 32768, perHour: 20, perDay: 100, allowlist: 20 };

/** Problem with the whole set (to send), or null. */
export function setProblem(set) {
  if (set.rules.length > LIMITS.rules) return `At most ${LIMITS.rules} rules.`;
  // Measured the way the phone's JSON library writes it, which escapes "/" as "\/".
  const json = JSON.stringify(set).replaceAll("/", "\\/");
  if (new TextEncoder().encode(json).length > LIMITS.setBytes) return "The rules are too large in total. Remove or shorten some.";
  return null;
}

const NUMBER = /^\+?[0-9]{3,15}$/;
const PROPOSAL = /^[0-9a-f]{0,32}$/;

/** The allowlist from a phone message ([{n, a, p}]), keeping only well-formed entries; null if absent. */
export function cleanAllow(a) {
  if (!Array.isArray(a)) return null;
  return a
    .filter((e) => e && NUMBER.test(String(e.n)) && PROPOSAL.test(String(e.p ?? "")))
    .slice(0, LIMITS.allowlist)
    .map((e) => ({ n: String(e.n), a: e.a === true, p: String(e.p ?? "") }));
}

/** Operators offered in the editor. "Negated" ones are stored as {not: leaf}. */
export const OPS = [
  { key: "contains", label: "contains", op: "contains", not: false },
  { key: "notContains", label: "does not contain", op: "contains", not: true },
  { key: "equals", label: "is exactly", op: "equals", not: false },
  { key: "notEquals", label: "is not", op: "equals", not: true },
  { key: "startsWith", label: "starts with", op: "startsWith", not: false },
  { key: "notStartsWith", label: "does not start with", op: "startsWith", not: true },
  { key: "endsWith", label: "ends with", op: "endsWith", not: false },
  { key: "notEndsWith", label: "does not end with", op: "endsWith", not: true },
];
export const FIELDS = [
  { key: "sender", label: "Sender" },
  { key: "body", label: "Message text" },
];

const opByKey = (k) => OPS.find((o) => o.key === k);

/**
 * Editor model: two levels, each with its own AND/OR. A rule is a list of groups joined by
 * `match` ("any" = OR, "all" = AND); a group is a list of conditions joined by its own `match`.
 * That covers "(A and B) or C" as well as "A and (B or C)" without nesting menus. A condition is
 * { field, op (an OPS key), value }.
 */
export function blankRule() {
  return { id: newId(), name: "", enabled: true, match: "any", groups: [blankGroup("sender")], forwardTo: [] };
}

export function blankGroup(field = "body") {
  return { match: "all", conds: [{ field, op: "contains", value: "" }] };
}

export function newId() {
  const b = crypto.getRandomValues(new Uint8Array(6));
  return "r" + [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

function leafOf(c) {
  const o = opByKey(c.op);
  const leaf = { field: c.field, op: o.op, value: c.value };
  return o.not ? { not: leaf } : leaf;
}

const join = (match, parts) => (parts.length === 1 ? parts[0] : { [match]: parts });

/** Editor model to the wire format, without needless wrapping (one group, one condition). */
export function toWire(rule) {
  return {
    id: rule.id,
    name: rule.name.trim() || rule.id,
    enabled: rule.enabled,
    when: join(rule.match, rule.groups.map((g) => join(g.match, g.conds.map(leafOf)))),
    forwardTo: [...rule.forwardTo],
  };
}

/** Exactly one of all/any/not/field, as the phone requires: no node can read two ways. */
const oneKind = (n) => !!n && typeof n === "object" && ["all", "any", "not", "field"].filter((k) => k in n).length === 1;

function condOf(n) {
  if (!oneKind(n)) return null;
  const neg = n.not && typeof n.not === "object";
  const leaf = neg ? n.not : n;
  if (!leaf || typeof leaf.field !== "string" || "all" in leaf || "any" in leaf || "not" in leaf) return null;
  const o = OPS.find((x) => x.op === leaf.op && x.not === !!neg);
  if (!o || !FIELDS.some((f) => f.key === leaf.field)) return null;
  return { field: leaf.field, op: o.key, value: String(leaf.value ?? "") };
}

/** {all|any: [...]} as [match, parts], or null. */
function split(n) {
  if (!oneKind(n)) return null;
  if (Array.isArray(n.all)) return ["all", n.all];
  if (Array.isArray(n.any)) return ["any", n.any];
  return null;
}

function groupOf(n) {
  const c = condOf(n);
  if (c) return { match: "all", conds: [c] };
  const sp = split(n);
  if (!sp || !sp[1].length) return null;
  const conds = sp[1].map(condOf);
  return conds.every(Boolean) ? { match: sp[0], conds } : null;
}

/** Wire format to the editor model, or null if the condition has a shape the editor cannot show. */
export function fromWire(r) {
  const one = groupOf(r?.when); // a single group (one condition, or conditions joined one way)
  let match = "any";
  let groups = one ? [one] : null;
  if (!groups) {
    const sp = split(r?.when);
    if (!sp || !sp[1].length) return null;
    match = sp[0];
    groups = sp[1].map(groupOf);
    if (!groups.every(Boolean)) return null;
  }
  return {
    id: String(r.id),
    name: String(r.name ?? ""),
    enabled: r.enabled !== false,
    match,
    groups,
    forwardTo: Array.isArray(r.forwardTo) ? r.forwardTo.map(String) : [],
  };
}

function countNodes(n) {
  if (Array.isArray(n.all)) return 1 + n.all.reduce((s, x) => s + countNodes(x), 0);
  if (Array.isArray(n.any)) return 1 + n.any.reduce((s, x) => s + countNodes(x), 0);
  if (n.not) return 1 + countNodes(n.not);
  return 1;
}

/** Problems with one rule, as sentences for the user; empty when it is fine to send. */
export function problems(rule, approved, allRules = []) {
  const out = [];
  if (!rule.name.trim()) out.push("Give the rule a name (it is shown on forwarded copies).");
  if (rule.name.trim().length > LIMITS.name) out.push(`Keep the name under ${LIMITS.name} characters.`);
  if (allRules.some((r) => r.id !== rule.id && r.name.trim() && r.name.trim().toLowerCase() === rule.name.trim().toLowerCase())) {
    out.push("Another rule has this name; forwarded copies show the rule name, so pick a different one.");
  }
  rule.groups.forEach((g, gi) => g.conds.forEach((c, ci) => {
    const where = rule.groups.length > 1 ? `Group ${gi + 1}, condition ${ci + 1}` : `Condition ${ci + 1}`;
    if (isBlank(c.value)) out.push(`${where}: type the text to look for.`);
    else if (c.value.length > LIMITS.value) out.push(`${where}: at most ${LIMITS.value} characters.`);
  }));
  if (rule.groups.every((g) => g.conds.every((c) => !isBlank(c.value))) && matches(rule, "", "")) {
    out.push("This would forward almost every message (it even matches an empty one). Add a positive condition such as \"contains\".");
  }
  const nodes = countNodes(toWire(rule).when);
  if (nodes > LIMITS.nodes) out.push(`Too many conditions (${nodes} parts, the phone allows ${LIMITS.nodes}). Split it into two rules.`);
  if (!rule.forwardTo.length) out.push("Pick at least one number to forward to.");
  if (rule.forwardTo.length > LIMITS.destinations) out.push(`At most ${LIMITS.destinations} numbers per rule.`);
  const stale = rule.forwardTo.filter((n) => !approved.includes(n));
  if (stale.length) out.push(`Not approved on the phone any more: ${stale.join(", ")}. Untick it or approve it again.`);
  return out;
}

const q = (s) => `"${s}"`;

/** Spaces only, by the phone's definition too (it also counts U+001C..U+001F as space). */
const isBlank = (v) => !/[^\s\u001c-\u001f]/.test(v);

/** Plain-English preview, e.g. 'sender contains "BANK" and text contains "OTP", or sender is exactly "X"'. */
export function describe(rule) {
  const one = (c) => `${c.field === "sender" ? "the sender" : "the text"} ${opByKey(c.op).label} ${q(c.value || "…")}`;
  const word = (m) => (m === "all" ? " and " : " or ");
  const groups = rule.groups.map((g) => g.conds.map(one).join(word(g.match)));
  if (groups.length === 1) return groups[0];
  return rule.groups.map((g, i) => (g.conds.length > 1 ? `(${groups[i]})` : groups[i])).join(rule.match === "all" ? ", and " : ", or ");
}

/** Same matching as the phone (case-insensitive, plain text). Only a preview: the phone decides. */
export function matches(rule, sender, body) {
  const test = (c) => {
    const text = (c.field === "sender" ? sender : body).toLowerCase();
    const v = c.value.toLowerCase();
    const o = opByKey(c.op);
    const hit = o.op === "contains" ? text.includes(v) : o.op === "equals" ? text === v : o.op === "startsWith" ? text.startsWith(v) : text.endsWith(v);
    return o.not ? !hit : hit;
  };
  const group = (g) => (g.match === "all" ? g.conds.every(test) : g.conds.some(test));
  return rule.match === "all" ? rule.groups.every(group) : rule.groups.some(group);
}
