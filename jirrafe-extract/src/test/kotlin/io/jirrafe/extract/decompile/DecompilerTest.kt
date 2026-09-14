package io.jirrafe.extract.decompile

import io.jirrafe.extract.Fixtures
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecompilerTest {
    @Test
    fun decompilesOneClassAndCaches() {
        val cache = Files.createTempDirectory("jirrafe-decompiled")
        val decompiler = Decompiler(cache)

        val file = assertNotNull(decompiler.decompile(Fixtures.internalLibJar, "com.example.lib.DefaultGreeter"))
        val text = Files.readString(file)
        assertTrue(text.startsWith("// Decompiled by Vineflower"), text)
        assertTrue(text.contains("public class DefaultGreeter implements Greeter"), text)
        assertTrue(text.contains("this.prefix + \", \" + name + \"!\""), text)
        assertTrue(file.startsWith(cache) && file.toString().replace('\\', '/').endsWith("com/example/lib/DefaultGreeter.java"))

        val before = Files.getLastModifiedTime(file)
        assertEquals(file, decompiler.decompile(Fixtures.internalLibJar, "com.example.lib.DefaultGreeter"))
        assertEquals(before, Files.getLastModifiedTime(file), "second call is served from the cache")

        assertNull(decompiler.decompile(Fixtures.internalLibJar, "com.example.lib.Missing"))
    }
}
