package io.jirrafe.extract.source

import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Origin
import io.jirrafe.core.store.GraphStore
import io.jirrafe.extract.Fixtures
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceExtractorTest {
    private val store = GraphStore.inMemory()
    private val result = SourceExtractor(store).extract(
        Fixtures.appJavaFiles(),
        SourceExtractor.Options(
            root = Fixtures.gradleMulti,
            classpath = listOf(Fixtures.internalLibJar, Fixtures.ktClasses, Fixtures.lombokJar),
            processorPath = listOf(Fixtures.lombokJar),
            generatedDir = Files.createTempDirectory("jirrafe-gen"),
            release = 17, origin = Origin.REPO, module = ":app", owner = "module::app",
        ),
    ).also { store.flush() }

    @Test
    fun parsesCleanlyWithDocsAndLines() {
        assertEquals(emptyList(), result.errors)
        assertEquals(3, result.files)

        val banner = assertNotNull(store.node("com.example.app.Main#banner(java.lang.String)"))
        assertEquals(Origin.REPO, banner.origin)
        assertEquals("Crosses two boundaries: the internal jar and the Kotlin module.", banner.doc)
        assertEquals("static banner(java.lang.String): java.lang.String", banner.signature)
        assertTrue(banner.startLine!! in 10..20 && banner.endLine!! > banner.startLine!!)
        assertTrue(banner.file!!.endsWith("Main.java"))
        assertEquals(":app", banner.module)

        val file = assertNotNull(store.node("file:app/src/main/java/com/example/app/Main.java"))
        assertEquals(NodeKind.FILE, file.kind)
        assertTrue(store.edgesFrom(file.id, EdgeKind.CONTAINS).map { it.to }.contains("com.example.app.Main"))
        assertEquals(
            setOf("com.example.kt.Formatter", "com.example.lib.Greeter", "com.example.lib.Greeters"),
            store.edgesFrom(file.id, EdgeKind.IMPORTS).map { it.to }.toSet(),
        )
        val calls = store.edgesFrom(banner.id, EdgeKind.CALLS).map { it.to }.toSet()
        assertEquals(
            setOf("com.example.lib.Greeters#standard()", "com.example.lib.Greeter#greet(java.lang.String)", "com.example.kt.Formatter#shout(java.lang.String)"),
            calls,
        )
        assertEquals(listOf("com.example.kt.Formatter#INSTANCE"), store.edgesFrom(banner.id, EdgeKind.READS_FIELD).map { it.to })
    }

    @Test
    fun modernConstructsGetBytecodeCompatibleIds() {
        val circle = assertNotNull(store.node("com.example.app.Features\$Circle"))
        assertEquals(NodeKind.RECORD, circle.kind)
        assertEquals(NodeKind.INTERFACE, store.node("com.example.app.Features\$Shape")?.kind)
        assertEquals(NodeKind.ENUM, store.node("com.example.app.Features\$Color")?.kind)
        assertEquals(listOf("com.example.app.Features\$Shape"), store.edgesFrom(circle.id, EdgeKind.IMPLEMENTS).map { it.to })
        assertEquals(listOf("com.example.app.Features"), store.edgesTo(circle.id, EdgeKind.CONTAINS).map { it.from }.filter { !it.startsWith("file:") })

        // pattern matching + switch expression
        val describe = assertNotNull(store.node("com.example.app.Features#describe(com.example.app.Features\$Shape)"))
        val describeCalls = store.edgesFrom(describe.id, EdgeKind.CALLS).map { it.to }.toSet()
        assertTrue("com.example.app.Features\$Circle#r()" in describeCalls, describeCalls.toString())
        assertTrue("com.example.app.Features\$Shape#area()" in describeCalls, describeCalls.toString())
        assertTrue(describe.attrs["strings"]!!.contains("big square"))
        // varargs
        assertNotNull(store.node("com.example.app.Features#total(com.example.app.Features\$Shape[])"))
        // method reference and generics
        val names = assertNotNull(store.node("com.example.app.Features#names(java.util.List)"))
        assertEquals(listOf(describe.id), store.edgesFrom(names.id, EdgeKind.CALLS).map { it.to })
        // lambda body attributed to the enclosing method
        val task = assertNotNull(store.node("com.example.app.Features#task(java.lang.String)"))
        assertEquals(
            setOf("com.example.lib.Greeters#standard()", "com.example.lib.Greeter#greet(java.lang.String)"),
            store.edgesFrom(task.id, EdgeKind.CALLS).map { it.to }.toSet(),
        )
        // inner and enum constructors carry the synthetic parameters bytecode has
        assertNotNull(store.node("com.example.app.Features\$Inner#<init>(com.example.app.Features)"))
        assertNull(store.node("com.example.app.Features\$Inner#<init>()"))
        assertNotNull(store.node("com.example.app.Features\$Color#<init>(java.lang.String,int)"))
        // local and anonymous classes
        assertEquals(NodeKind.CLASS, store.node("com.example.app.Features\$1Local")?.kind)
        assertTrue(store.node("com.example.app.Features\$1") != null || store.node("com.example.app.Features\$2") != null)
        // text block and static import land on the class node (initializers)
        val features = assertNotNull(store.node("com.example.app.Features"))
        assertTrue(features.attrs["strings"]!!.contains("multi"), features.attrs.toString())
        assertTrue("com.example.lib.Greeters#standard()" in store.edgesFrom(features.id, EdgeKind.CALLS).map { it.to })
        assertTrue("com.example.app.Features#fromStaticImport" in store.edgesFrom(features.id, EdgeKind.WRITES_FIELD).map { it.to })
        // overrides
        assertEquals(
            listOf("com.example.app.Features\$Shape#area()"),
            store.edgesFrom("com.example.app.Features\$Circle#area()", EdgeKind.OVERRIDES).map { it.to },
        )
    }

    @Test
    fun lombokMembersAreVisible() {
        val getter = assertNotNull(store.node("com.example.app.Model#getName()"))
        assertEquals(Origin.REPO, getter.origin)
        assertNotNull(store.node("com.example.app.Model#setAge(int)"))
        assertEquals("lombok.Data", store.edgesFrom("com.example.app.Model", EdgeKind.ANNOTATED_WITH).single().to)
        assertTrue(store.node("com.example.app.Model")!!.attrs["annotations"]!!.contains("lombok.Data"))
    }

    @Test
    fun unresolvedCallsAreSkippedNotGuessed() {
        val dir = Files.createTempDirectory("jirrafe-broken")
        val src = dir.resolve("Broken.java")
        Files.writeString(
            src, """
            package com.example.broken;
            import com.example.lib.Greeters;
            public class Broken {
                String ok() { return Greeters.standard().greet("x"); }
                void bad() { Missing.call(Greeters.standard()); Greeters.standard().greet(Missing.value()); }
            }
            """.trimIndent()
        )
        val store = GraphStore.inMemory()
        val r = SourceExtractor(store).extract(
            listOf(src),
            SourceExtractor.Options(
                root = dir, classpath = listOf(Fixtures.internalLibJar), generatedDir = dir.resolve("gen"),
                release = 17, origin = Origin.REPO, module = ":x", owner = "module::x",
            ),
        )
        store.flush()
        assertTrue(r.errors.isNotEmpty())
        assertNotNull(store.node("com.example.broken.Broken#bad()"))
        assertEquals(setOf("com.example.lib.Greeters#standard()", "com.example.lib.Greeter#greet(java.lang.String)"),
            store.edgesFrom("com.example.broken.Broken#ok()", EdgeKind.CALLS).map { it.to }.toSet())
        val bad = store.edgesFrom("com.example.broken.Broken#bad()", EdgeKind.CALLS).map { it.to }
        assertEquals(listOf("com.example.lib.Greeters#standard()"), bad, "only the fully resolved call survives")
    }
}
