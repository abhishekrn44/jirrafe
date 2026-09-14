package io.jirrafe.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One method parameter as recorded in `attrs.params`. [type] keeps generics when known. */
@Serializable
data class Param(val name: String, val type: String, val annotations: Map<String, Map<String, String>> = emptyMap())

/** Encoders and decoders for the structured values both extractors put into [Node.attrs]. */
object Attrs {
    const val ANNOTATIONS = "annotations"
    const val PARAMS = "params"
    const val STRINGS = "strings"
    const val SUPERTYPES = "supertypes"

    private val json = Json { ignoreUnknownKeys = true }
    private val annotations = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    private val params = ListSerializer(Param.serializer())
    private val strings = ListSerializer(String.serializer())

    /** `{annotationFqn: {member: value}}`; array values are comma-joined, enum values are constant names. */
    fun annotations(node: Node): Map<String, Map<String, String>> =
        node.attrs[ANNOTATIONS]?.let { json.decodeFromString(annotations, it) } ?: emptyMap()

    fun encodeAnnotations(value: Map<String, Map<String, String>>): String? =
        if (value.isEmpty()) null else json.encodeToString(annotations, value)

    fun params(node: Node): List<Param> = node.attrs[PARAMS]?.let { json.decodeFromString(params, it) } ?: emptyList()

    fun encodeParams(value: List<Param>): String? = if (value.isEmpty()) null else json.encodeToString(params, value)

    fun strings(node: Node): List<String> = node.attrs[STRINGS]?.let { json.decodeFromString(strings, it) } ?: emptyList()

    fun encodeStrings(value: List<String>): String? = if (value.isEmpty()) null else json.encodeToString(strings, value)
}
