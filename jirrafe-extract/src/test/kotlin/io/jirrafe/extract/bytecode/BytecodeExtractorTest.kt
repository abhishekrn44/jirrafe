package io.jirrafe.extract.bytecode

import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.store.GraphStore
import io.jirrafe.extract.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BytecodeExtractorTest {
    private val store = GraphStore.inMemory().also { store ->
        val extractor = BytecodeExtractor(store)
        extractor.extractJar(Fixtures.internalLibJar, BytecodeExtractor.Context(Origin.BYTECODE, null, "com.example.fixtures:internal-lib:1.0.0", "artifact:lib"))
        extractor.extractDir(Fixtures.appClasses, BytecodeExtractor.Context(Origin.BYTECODE, ":app", null, "module::app"))
        extractor.extractDir(Fixtures.ktClasses, BytecodeExtractor.Context(Origin.BYTECODE, ":kt-module", null, "module::kt-module"))
        store.flush()
    }

    @Test
    fun classesMembersAndHierarchy() {
        val greeter = assertNotNull(store.node("com.example.lib.DefaultGreeter"))
        assertEquals(NodeKind.CLASS, greeter.kind)
        assertEquals("public class DefaultGreeter implements Greeter", greeter.signature)
        assertEquals("DefaultGreeter.java", greeter.attrs["sourceFile"])
        assertEquals("com.example.fixtures:internal-lib:1.0.0", greeter.artifact)
        assertNotNull(greeter.sha)
        assertEquals(NodeKind.INTERFACE, store.node("com.example.lib.Greeter")?.kind)
        assertEquals(listOf("com.example.lib.Greeter"), store.edgesFrom(greeter.id, EdgeKind.IMPLEMENTS).map { it.to })
        assertEquals(listOf("com.example.lib.Greeter"), store.edgesFrom("com.example.lib.Greeters", EdgeKind.USES_TYPE).map { it.to })

        val field = assertNotNull(store.node("com.example.lib.DefaultGreeter#prefix"))
        assertEquals("private final prefix: java.lang.String", field.signature)
        val ctor = assertNotNull(store.node("com.example.lib.DefaultGreeter#<init>(java.lang.String)"))
        assertEquals(NodeKind.CONSTRUCTOR, ctor.kind)
        assertEquals(listOf(field.id), store.edgesFrom(ctor.id, EdgeKind.WRITES_FIELD).map { it.to })

        val greet = assertNotNull(store.node("com.example.lib.DefaultGreeter#greet(java.lang.String)"))
        assertEquals("public greet(java.lang.String): java.lang.String", greet.signature)
        assertTrue(greet.startLine!! > 0 && greet.endLine!! >= greet.startLine!!)
        assertEquals(listOf(field.id), store.edgesFrom(greet.id, EdgeKind.READS_FIELD).map { it.to })
        assertTrue(greet.attrs["strings"]!!.contains(", "), "concat constants harvested: ${greet.attrs}")

        assertEquals(listOf("com.example.lib"), store.edgesFrom("artifact:lib", EdgeKind.CONTAINS).map { it.to })
        assertEquals(NodeKind.PACKAGE, store.node("com.example.lib")?.kind)
        assertTrue(store.edgesFrom("com.example.lib", EdgeKind.CONTAINS).map { it.to }.containsAll(listOf(greeter.id, "com.example.lib.Greeters")))
    }

    @Test
    fun callsAcrossJarAndKotlinModule() {
        val standard = assertNotNull(store.node("com.example.lib.Greeters#standard()"))
        assertEquals(listOf("com.example.lib.DefaultGreeter#<init>(java.lang.String)"), store.edgesFrom(standard.id, EdgeKind.CALLS).map { it.to })
        assertEquals("[\"Hello\"]", standard.attrs["strings"])

        val banner = assertNotNull(store.node("com.example.app.Main#banner(java.lang.String)"))
        assertEquals(":app", banner.module)
        val calls = store.edgesFrom(banner.id, EdgeKind.CALLS).map { it.to }.toSet()
        assertTrue(calls.contains("com.example.lib.Greeters#standard()"), calls.toString())
        assertTrue(calls.contains("com.example.lib.Greeter#greet(java.lang.String)"), calls.toString())
        assertTrue(calls.contains("com.example.kt.Formatter#shout(java.lang.String)"), calls.toString())
        assertEquals(listOf("com.example.kt.Formatter#INSTANCE"), store.edgesFrom(banner.id, EdgeKind.READS_FIELD).map { it.to })
        assertTrue(store.edgesFrom(banner.id).none { it.to.startsWith("java.") }, "no edges into the JDK")

        val formatter = assertNotNull(store.node("com.example.kt.Formatter"))
        assertEquals(":kt-module", formatter.module)
        assertNotNull(store.node("com.example.kt.Formatter#shout(java.lang.String)"))
        assertNull(store.node("com.example.kt.Formatter#\$VALUES"))
    }
}
