package dev.smsrelay.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class RulesTest {
    private fun leaf(field: String, op: String, value: String) = JSONObject().put("field", field).put("op", op).put("value", value)

    private fun set(vararg rules: JSONObject, version: Long = 1) =
        JSONObject().put("version", version).put("rules", JSONArray(rules.toList()))

    private fun rule(id: String, `when`: JSONObject, to: List<String> = listOf("+15555550100")) =
        JSONObject().put("id", id).put("name", id).put("when", `when`).put("forwardTo", JSONArray(to))

    /** (body contains XXX and body contains YYY) or sender is ZZZ */
    private val example = JSONObject().put("any", JSONArray(listOf(
        JSONObject().put("all", JSONArray(listOf(leaf("body", "contains", "XXX"), leaf("body", "contains", "YYY")))),
        leaf("sender", "equals", "ZZZ"),
    )))

    @Test fun exampleRuleMatchesAsDescribed() {
        val r = Rules.parse(set(rule("r1", example))).rules.single()
        assertTrue(Rules.matches(r.`when`, "SOMEONE", "code XXX and yyy here"))
        assertFalse(Rules.matches(r.`when`, "SOMEONE", "only XXX here"))
        assertTrue(Rules.matches(r.`when`, "zzz", "anything"))
        assertEquals("((body contains \"XXX\" and body contains \"YYY\") or sender is \"ZZZ\")", Rules.describe(r.`when`))
    }

    @Test fun operatorsAndNot() {
        val c = Rules.parse(set(rule("r", JSONObject().put("all", JSONArray(listOf(
            leaf("sender", "startsWith", "EX"), leaf("body", "endsWith", "end"),
            JSONObject().put("not", leaf("body", "contains", "spam")),
        )))))).rules.single().`when`
        assertTrue(Rules.matches(c, "EXBANK", "the end"))
        assertFalse(Rules.matches(c, "EXBANK", "spam at the end"))
        assertFalse(Rules.matches(c, "BANK", "the end"))
    }

    @Test fun nameFallsBackToTheId() {
        val r = rule("r1", example).put("name", "   ")
        assertEquals("r1", Rules.parse(set(r)).rules.single().name)
    }

    @Test fun roundTripsThroughJson() {
        val s = Rules.parse(set(rule("r1", example), version = 3))
        assertEquals(s, Rules.parse(Rules.toJson(s)))
    }

    @Test fun rejectsInvalidOrOversizedRules() {
        val bad = listOf(
            set(rule("r", example), version = 0),
            set(rule("Bad Id", example)),
            set(rule("r", example), rule("r", example)),
            set(rule("r", leaf("title", "contains", "x"))),
            set(rule("r", leaf("body", "regex", ".*"))),
            set(rule("r", leaf("body", "contains", ""))),
            set(rule("r", leaf("body", "contains", "x".repeat(Rules.MAX_VALUE + 1)))),
            set(rule("r", example, to = emptyList())),
            set(rule("r", example, to = listOf("not a number"))),
            set(rule("r", example, to = List(Rules.MAX_DESTINATIONS + 1) { "+1555555010$it" })),
            set(rule("r", JSONObject().put("any", JSONArray(List(Rules.MAX_NODES_PER_RULE) { leaf("body", "contains", "a$it") })))),
            set(*Array(Rules.MAX_RULES + 1) { rule("r$it", example) }),
            set(rule("r", leaf("body", "contains", "   "))),
            set(rule("r", JSONObject().put("all", JSONArray()))),
            set(rule("r", JSONObject().put("any", JSONArray()))),
            set(rule("r", leaf("body", "contains", "x").put("all", JSONArray(listOf(leaf("body", "contains", "y")))))),
            set(rule("r", JSONObject().put("not", leaf("body", "contains", "x")).put("any", JSONArray(listOf(leaf("body", "contains", "y")))))),
            set(rule("r", example), version = Rules.MAX_VERSION + 1),
        )
        val long = "x".repeat(Rules.MAX_VALUE)
        val big = set(*Array(Rules.MAX_RULES) { rule("r$it", JSONObject().put("any", JSONArray(List(4) { leaf("body", "contains", long) }))) })
        var deep = leaf("body", "contains", "x")
        repeat(Rules.MAX_DEPTH) { deep = JSONObject().put("not", deep) }
        for (j in bad + set(rule("r", deep)) + big) {
            try {
                Rules.parse(j)
                fail("accepted ${j.toString().take(120)}")
            } catch (e: Rules.Invalid) {
            }
        }
    }

    @Test fun matchingAgreesWithTheSharedVectors() {
        val dir = File(System.getProperty("user.dir")!!).resolve("../../test-vectors").canonicalFile
        val cases = JSONObject(dir.resolve("rules.json").readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val r = Rules.parse(set(rule("r", c.getJSONObject("when")))).rules.single()
            assertEquals(c.toString(), c.getBoolean("match"), Rules.matches(r.`when`, c.getString("sender"), c.getString("body")))
        }
    }

    @Test fun limitsMatchTheSharedVectors() {
        val dir = File(System.getProperty("user.dir")!!).resolve("../../test-vectors").canonicalFile
        val l = JSONObject(dir.resolve("rules.json").readText()).getJSONObject("limits")
        assertEquals(l.getInt("rules"), Rules.MAX_RULES)
        assertEquals(l.getInt("nodes"), Rules.MAX_NODES_PER_RULE)
        assertEquals(l.getInt("value"), Rules.MAX_VALUE)
        assertEquals(l.getInt("name"), Rules.MAX_NAME)
        assertEquals(l.getInt("destinations"), Rules.MAX_DESTINATIONS)
        assertEquals(l.getInt("setBytes"), Rules.MAX_SET_BYTES)
        assertEquals(l.getInt("perHour"), PhoneCore.FWD_PER_HOUR)
        assertEquals(l.getInt("perDay"), PhoneCore.FWD_PER_DAY)
        assertEquals(l.getInt("allowlist"), Allowlist.MAX_ENTRIES)
    }

    @Test fun allowlistEntriesDefaultToNotApproved() {
        val l = Allowlist.parse("[{\"n\":\"+15555550100\"},{\"n\":\"bad\",\"a\":true},{\"n\":\"+15555550101\",\"a\":true,\"p\":\"ab\"}]")
        assertEquals(listOf(Allowlist.Entry("+15555550100", false, ""), Allowlist.Entry("+15555550101", true, "ab")), l)
        assertEquals(setOf("+15555550101"), Allowlist.active(l))
    }

    @Test fun numbersNormalise() {
        assertEquals("+15555550100", Allowlist.normalize("+1 (555) 555-0100"))
        assertEquals(null, Allowlist.normalize("12"))
        assertEquals(null, Allowlist.normalize("+1555abc"))
    }
}
