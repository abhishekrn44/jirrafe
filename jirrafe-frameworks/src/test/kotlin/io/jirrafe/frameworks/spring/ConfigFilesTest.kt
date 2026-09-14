package io.jirrafe.frameworks.spring

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigFilesTest {
    @Test
    fun flattensYamlAndProperties() {
        val dir = Files.createTempDirectory("cfg")
        Files.writeString(
            dir.resolve("application.yml"), """
            server:
              port: 8080   # comment
              ssl:
                enabled: "true"
            list:
              - a
              - b
            ---
            spring:
              profiles: prod
            name: x
            """.trimIndent()
        )
        Files.writeString(dir.resolve("application-prod.properties"), "# c\nserver.port=9090\nkey: value\n")
        val files = ConfigFiles.find(listOf(dir))
        assertEquals(listOf("application-prod.properties", "application.yml"), files.map { it.fileName.toString() })

        val yml = ConfigFiles.read(files[1]).associate { it.key to (it.value to it.line) }
        assertEquals("8080" to 2, yml["server.port"])
        assertEquals("true" to 4, yml["server.ssl.enabled"])
        assertEquals("x" to 11, yml["name"])
        assertEquals(setOf("server.port", "server.ssl.enabled", "spring.profiles", "name"), yml.keys)

        val props = ConfigFiles.read(files[0])
        assertEquals("prod", props[0].profile)
        assertEquals(listOf("server.port" to "9090", "key" to "value"), props.map { it.key to it.value })
    }
}
