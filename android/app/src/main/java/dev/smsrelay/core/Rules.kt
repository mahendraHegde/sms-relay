package dev.smsrelay.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Forwarding rules: which incoming SMS to forward to which numbers. Authored in the reader,
 * applied on the phone. Deliberately small: plain string matching (no regular expressions, so a
 * crafted SMS cannot make matching slow), strict size limits, and destinations that must already
 * be on the phone's approved allowlist (see Allowlist). The reader mirrors the limits and the
 * matching in reader/rules.js; test-vectors/rules.json keeps the two in step.
 */
object Rules {
    const val MAX_RULES = 50
    const val MAX_NODES_PER_RULE = 20
    const val MAX_DEPTH = 4
    const val MAX_VALUE = 200
    const val MAX_NAME = 60
    const val MAX_DESTINATIONS = 5
    /** The whole set also rides in status messages, which must fit one envelope (64 KiB). */
    const val MAX_SET_BYTES = 32_768
    /** Largest version the reader can still count past exactly (JavaScript numbers). */
    const val MAX_VERSION = 9_007_199_254_740_991L
    private val ID = Regex("^[a-z0-9-]{1,32}$")
    private val NODE_KEYS = listOf("all", "any", "not", "field")

    enum class Field(val wire: String) { SENDER("sender"), BODY("body") }
    enum class Op(val wire: String, val label: String) {
        CONTAINS("contains", "contains"),
        EQUALS("equals", "is"),
        STARTS_WITH("startsWith", "starts with"),
        ENDS_WITH("endsWith", "ends with"),
    }

    sealed class Cond {
        data class Leaf(val field: Field, val op: Op, val value: String) : Cond() {
            /** Compared against lower-cased text: whole-string folding, the same as the reader's preview. */
            val folded = value.lowercase()
        }
        data class All(val of: List<Cond>) : Cond()
        data class AnyOf(val of: List<Cond>) : Cond()
        data class Not(val of: Cond) : Cond()
    }

    data class Rule(val id: String, val name: String, val enabled: Boolean, val `when`: Cond, val forwardTo: List<String>)

    data class RuleSet(val version: Long, val rules: List<Rule>)

    class Invalid(msg: String) : Exception(msg)

    /** Parse and validate; throws [Invalid] with a reason the reader can show. */
    fun parse(j: JSONObject): RuleSet {
        val version = j.optLong("version", -1)
        if (version !in 1..MAX_VERSION) throw Invalid("version must be a positive number")
        val arr = j.optJSONArray("rules") ?: throw Invalid("rules missing")
        if (arr.length() > MAX_RULES) throw Invalid("at most $MAX_RULES rules")
        val ids = HashSet<String>()
        val rules = (0 until arr.length()).map { i ->
            val r = arr.optJSONObject(i) ?: throw Invalid("rule ${i + 1} is not an object")
            val id = r.optString("id")
            if (!ID.matches(id) || !ids.add(id)) throw Invalid("rule ${i + 1}: id must be unique, a-z 0-9 -")
            val name = r.optString("name").trim().take(MAX_NAME)
            val dests = r.optJSONArray("forwardTo") ?: throw Invalid("rule $id: forwardTo missing")
            if (dests.length() !in 1..MAX_DESTINATIONS) throw Invalid("rule $id: 1 to $MAX_DESTINATIONS numbers")
            val forwardTo = (0 until dests.length()).map { Allowlist.normalize(dests.optString(it)) ?: throw Invalid("rule $id: bad number") }.distinct()
            val nodes = intArrayOf(0)
            val cond = cond(r.optJSONObject("when") ?: throw Invalid("rule $id: when missing"), 1, nodes, id)
            Rule(id, name.ifEmpty { id }, r.optBoolean("enabled", true), cond, forwardTo)
        }
        return RuleSet(version, rules).also {
            if (toJson(it).toString().toByteArray().size > MAX_SET_BYTES) throw Invalid("rules too large (at most ${MAX_SET_BYTES / 1024} KiB in total)")
        }
    }

    private fun cond(j: JSONObject, depth: Int, nodes: IntArray, id: String): Cond {
        if (depth > MAX_DEPTH) throw Invalid("rule $id: conditions nested too deeply")
        if (++nodes[0] > MAX_NODES_PER_RULE) throw Invalid("rule $id: too many conditions")
        // Exactly one kind per node, so no reader can display a node differently from how it applies.
        if (NODE_KEYS.count { j.has(it) } != 1) throw Invalid("rule $id: each condition needs exactly one of all, any, not or field")
        fun list(key: String): List<Cond> {
            val a = j.optJSONArray(key) ?: throw Invalid("rule $id: $key must be a list")
            if (a.length() == 0) throw Invalid("rule $id: empty $key")
            return (0 until a.length()).map { cond(a.optJSONObject(it) ?: throw Invalid("rule $id: bad condition"), depth + 1, nodes, id) }
        }
        return when {
            j.has("all") -> Cond.All(list("all"))
            j.has("any") -> Cond.AnyOf(list("any"))
            j.has("not") -> Cond.Not(cond(j.optJSONObject("not") ?: throw Invalid("rule $id: bad not"), depth + 1, nodes, id))
            else -> {
                val field = Field.entries.firstOrNull { it.wire == j.optString("field") } ?: throw Invalid("rule $id: field must be sender or body")
                val op = Op.entries.firstOrNull { it.wire == j.optString("op") }
                    ?: throw Invalid("rule $id: op must be ${Op.entries.joinToString { it.wire }}")
                val value = j.optString("value")
                if (value.isBlank() || value.length > MAX_VALUE) throw Invalid("rule $id: value must be 1 to $MAX_VALUE characters, not only spaces")
                Cond.Leaf(field, op, value)
            }
        }
    }

    /** Case-insensitive (whole-string lower-casing); linear in the message size per condition. */
    fun matches(c: Cond, sender: String, body: String): Boolean = matchFolded(c, sender.lowercase(), body.lowercase())

    private fun matchFolded(c: Cond, sender: String, body: String): Boolean = when (c) {
        is Cond.All -> c.of.all { matchFolded(it, sender, body) }
        is Cond.AnyOf -> c.of.any { matchFolded(it, sender, body) }
        is Cond.Not -> !matchFolded(c.of, sender, body)
        is Cond.Leaf -> {
            val text = if (c.field == Field.SENDER) sender else body
            when (c.op) {
                Op.CONTAINS -> text.contains(c.folded)
                Op.EQUALS -> text == c.folded
                Op.STARTS_WITH -> text.startsWith(c.folded)
                Op.ENDS_WITH -> text.endsWith(c.folded)
            }
        }
    }

    /** Back to JSON (for storage and for the reader). */
    fun toJson(set: RuleSet): JSONObject = JSONObject().put("version", set.version).put("rules", JSONArray(set.rules.map { r ->
        JSONObject().put("id", r.id).put("name", r.name).put("enabled", r.enabled).put("when", condJson(r.`when`))
            .put("forwardTo", JSONArray(r.forwardTo))
    }))

    private fun condJson(c: Cond): JSONObject = when (c) {
        is Cond.All -> JSONObject().put("all", JSONArray(c.of.map(::condJson)))
        is Cond.AnyOf -> JSONObject().put("any", JSONArray(c.of.map(::condJson)))
        is Cond.Not -> JSONObject().put("not", condJson(c.of))
        is Cond.Leaf -> JSONObject().put("field", c.field.wire).put("op", c.op.wire).put("value", c.value)
    }

    /** Human-readable condition, e.g. (body contains "A" and body contains "B") or sender is "C". */
    fun describe(c: Cond): String = when (c) {
        is Cond.All -> c.of.joinToString(" and ", "(", ")") { describe(it) }
        is Cond.AnyOf -> c.of.joinToString(" or ", "(", ")") { describe(it) }
        is Cond.Not -> "not " + describe(c.of)
        is Cond.Leaf -> "${c.field.wire} ${c.op.label} \"${c.value}\""
    }
}

/**
 * Forwarding destinations. A number is proposed on the phone (behind the screen lock) and becomes
 * active only when the reader approves that very proposal (its random [Entry.proposal] id): neither
 * the phone holder alone nor someone with the reader's keys alone can add a destination, and an
 * old approval cannot activate a number that was removed and proposed again. Removal is allowed
 * from either side.
 */
object Allowlist {
    const val MAX_ENTRIES = 20
    private val NUMBER = Regex("^\\+?[0-9]{3,15}$")

    data class Entry(val number: String, val active: Boolean, val proposal: String = "")

    /** Digits with an optional leading +; spaces, dashes and brackets dropped. Null if not a number. */
    fun normalize(raw: String): String? = raw.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }.takeIf { NUMBER.matches(it) }

    fun parse(json: String?): List<Entry> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            normalize(o.optString("n"))?.let { Entry(it, o.optBoolean("a", false), o.optString("p")) }
        }
    }.getOrDefault(emptyList())

    /** {n: number, a: approved, p: proposal id}: stored on the phone and reported to the reader. */
    fun toJsonArray(list: List<Entry>) = JSONArray(list.map { JSONObject().put("n", it.number).put("a", it.active).put("p", it.proposal) })

    fun toJson(list: List<Entry>): String = toJsonArray(list).toString()

    fun active(list: List<Entry>): Set<String> = list.filter { it.active }.map { it.number }.toSet()
}
