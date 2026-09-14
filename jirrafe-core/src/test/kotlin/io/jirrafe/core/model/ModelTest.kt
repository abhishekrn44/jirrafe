package io.jirrafe.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    @Test
    fun `node and edge round-trip through json`() {
        val node = Node(id = "m:1", kind = NodeKind.METHOD, fqn = "a.B#c()", origin = Origin.REPO, startLine = 3)
        val edge = Edge(from = "m:1", to = "m:2", kind = EdgeKind.CALLS, resolution = Resolution.EXACT)
        assertEquals(node, Json.decodeFromString<Node>(Json.encodeToString(node)))
        assertEquals(edge, Json.decodeFromString<Edge>(Json.encodeToString(edge)))
    }
}
