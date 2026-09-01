// Minimal Truth-compatible assertion surface used by the LinuxDroid tests.
// Semantics intentionally match Truth so test bodies are unmodified.
package com.google.common.truth

class AssertionFailure(msg: String) : AssertionError(msg)

private fun fail(msg: String): Nothing = throw AssertionFailure(msg)

private fun Number.isIntegral(): Boolean =
    this is Int || this is Long || this is Short || this is Byte

open class Subject(private val actual: Any?) {
    fun isEqualTo(expected: Any?) {
        val a = actual; val e = expected
        val eq = when {
            a is Iterable<*> && e is Iterable<*> -> a.toList() == e.toList()
            // Truth compares integral values across boxed types, so 2L == 2.
            a is Number && e is Number && a.isIntegral() && e.isIntegral() ->
                a.toLong() == e.toLong()
            else -> a == e
        }
        if (!eq) fail("expected <$e> (${e?.javaClass?.simpleName}) but was <$a> (${a?.javaClass?.simpleName})")
    }
    fun isNotEqualTo(other: Any?) { if (actual == other) fail("expected not to be <$other>") }
    fun isNull() { if (actual != null) fail("expected null but was <$actual>") }
    fun isNotNull() { if (actual == null) fail("expected non-null") }
    fun isTrue() { if (actual != true) fail("expected true but was <$actual>") }
    fun isFalse() { if (actual != false) fail("expected false but was <$actual>") }
    fun isAnyOf(vararg options: Any?) {
        if (actual !in options) fail("expected any of ${options.toList()} but was <$actual>")
    }
    fun isInstanceOf(clazz: Class<*>) {
        if (actual == null || !clazz.isInstance(actual)) {
            fail("expected instance of ${clazz.name} but was <${actual?.javaClass?.name}>")
        }
    }
    fun isAtLeast(min: Int) {
        val v = (actual as? Number)?.toLong() ?: fail("not a number: <$actual>")
        if (v < min) fail("expected at least $min but was $v")
    }
    fun hasMessageThat(): StringSubject {
        val t = actual as? Throwable ?: fail("not a Throwable: <$actual>")
        return StringSubject(t.message)
    }
}

class StringSubject(private val actual: String?) : Subject(actual) {
    fun contains(needle: String) {
        if (actual == null || !actual.contains(needle)) fail("<$actual> does not contain <$needle>")
    }
    fun doesNotContain(needle: String) {
        if (actual != null && actual.contains(needle)) fail("<$actual> unexpectedly contains <$needle>")
    }
    fun startsWith(prefix: String) {
        if (actual == null || !actual.startsWith(prefix)) fail("<$actual> does not start with <$prefix>")
    }
    fun endsWith(suffix: String) {
        if (actual == null || !actual.endsWith(suffix)) fail("<$actual> does not end with <$suffix>")
    }
}

class Ordered internal constructor(
    private val actual: List<Any?>,
    private val expected: List<Any?>,
) {
    fun inOrder() {
        if (actual != expected) fail("expected exactly $expected in order but was $actual")
    }
}

class IterableSubject(private val actual: Iterable<Any?>?) : Subject(actual) {
    private fun list() = actual?.toList() ?: fail("expected non-null iterable")
    fun contains(element: Any?) {
        if (!list().contains(element)) fail("${list()} does not contain <$element>")
    }
    fun doesNotContain(element: Any?) {
        if (list().contains(element)) fail("${list()} unexpectedly contains <$element>")
    }
    fun containsExactly(vararg elements: Any?): Ordered {
        val a = list(); val e = elements.toList()
        // Truth's containsExactly is order-independent unless inOrder() follows.
        if (a.size != e.size || !e.all { x -> a.count { it == x } == e.count { it == x } }) {
            fail("expected exactly $e but was $a")
        }
        return Ordered(a, e)
    }
    fun hasSize(size: Int) { if (list().size != size) fail("expected size $size but was ${list().size}") }
    fun isEmpty() { if (list().isNotEmpty()) fail("expected empty but was ${list()}") }
    fun isNotEmpty() { if (list().isEmpty()) fail("expected non-empty") }
    fun isInOrder() {
        val a = list().map { (it as Number).toLong() }
        if (a != a.sorted()) fail("expected ascending order but was $a")
    }
}

class MapSubject(private val actual: Map<*, *>?) : Subject(actual) {
    private fun map() = actual ?: fail("expected non-null map")
    fun containsEntry(key: Any?, value: Any?) {
        if (!map().containsKey(key) || map()[key] != value) {
            fail("expected entry <$key=$value> but was <${map()[key]}>")
        }
    }
    fun containsKey(key: Any?) { if (!map().containsKey(key)) fail("missing key <$key>") }
    fun doesNotContainKey(key: Any?) { if (map().containsKey(key)) fail("unexpected key <$key>") }
    fun isEmpty() { if (map().isNotEmpty()) fail("expected empty map but was ${map()}") }
    fun isNotEmpty() { if (map().isEmpty()) fail("expected non-empty map") }
}

object Truth {
    @JvmStatic fun assertThat(actual: Any?): Subject = Subject(actual)
    @JvmStatic fun assertThat(actual: String?): StringSubject = StringSubject(actual)
    @JvmStatic fun assertThat(actual: Iterable<Any?>?): IterableSubject = IterableSubject(actual)
    @JvmStatic fun assertThat(actual: Map<*, *>?): MapSubject = MapSubject(actual)
    @JvmStatic fun assertThat(actual: Int?): Subject = Subject(actual)
    @JvmStatic fun assertThat(actual: Boolean?): Subject = Subject(actual)
    @JvmStatic fun assertThat(actual: Throwable?): Subject = Subject(actual)
}
