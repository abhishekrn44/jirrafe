package io.jirrafe.core.knowledge

import io.jirrafe.core.model.Attrs
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.store.GraphStore

/**
 * Classifies classes as controller | service | repository | client | config | model | util from
 * annotations first, then naming, then edge direction. Unclassifiable classes get no layer.
 */
internal object Layers {
    val ORDER = listOf("controller", "service", "repository", "client", "config", "model", "util")

    /** Dependencies a layer must not have; everything else is allowed. */
    val FORBIDDEN: Map<String, Set<String>> = mapOf(
        "controller" to setOf("controller"),
        "service" to setOf("controller"),
        "repository" to setOf("controller", "service"),
        "client" to setOf("controller", "service", "repository"),
        "model" to setOf("controller", "service", "repository", "client"),
        "util" to setOf("controller", "service", "repository", "client"),
    )

    private val ANNOTATIONS = listOf(
        "controller" to setOf(
            "org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.RestController",
            "org.springframework.web.bind.annotation.ControllerAdvice", "org.springframework.web.bind.annotation.RestControllerAdvice",
            "jakarta.ws.rs.Path", "javax.ws.rs.Path",
        ),
        "client" to setOf("org.springframework.cloud.openfeign.FeignClient"),
        "config" to setOf(
            "org.springframework.context.annotation.Configuration", "org.springframework.boot.context.properties.ConfigurationProperties",
            "org.springframework.boot.autoconfigure.SpringBootApplication",
        ),
        "model" to setOf("jakarta.persistence.Entity", "javax.persistence.Entity", "jakarta.persistence.Embeddable", "org.springframework.data.mongodb.core.mapping.Document"),
        "service" to setOf("org.springframework.stereotype.Service"),
    )
    private const val COMPONENT = "org.springframework.stereotype.Component"

    private val SUFFIXES = listOf(
        "controller" to listOf("Controller", "Resource", "Endpoint", "RestApi"),
        "repository" to listOf("Repository", "Dao", "DAO", "Store"),
        "client" to listOf("Client", "Gateway", "Adapter", "Feign"),
        "config" to listOf("Config", "Configuration", "Properties", "Settings"),
        "util" to listOf("Util", "Utils", "Helper", "Helpers", "Support"),
        "model" to listOf("Dto", "DTO", "Entity", "Request", "Response", "Event", "Model", "Record", "Payload", "Vo", "VO"),
        "service" to listOf("Service", "Manager", "Handler", "Processor", "UseCase", "Facade", "Listener", "Consumer", "Producer", "Job", "Scheduler"),
    )

    fun classify(g: ClassGraph, store: GraphStore): Array<String?> {
        val result = arrayOfNulls<String>(g.classes.size)
        // A bare @Component says "bean", not which layer: the name decides, then service.
        for ((i, cls) in g.classes.withIndex()) {
            result[i] = byAnnotation(cls, store) ?: byName(cls) ?: "service".takeIf { COMPONENT in Attrs.annotations(cls) }
        }
        // Edge direction for the rest: talks to a repository -> service; only data -> model.
        for ((i, cls) in g.classes.withIndex()) {
            if (result[i] != null) continue
            val targets = g.deps[i].keys.mapNotNull { result[it] }
            result[i] = when {
                cls.kind == NodeKind.RECORD || cls.kind == NodeKind.ENUM -> "model"
                "repository" in targets || "client" in targets -> "service"
                g.deps[i].isEmpty() && isDataOnly(cls, g) -> "model"
                else -> null
            }
        }
        return result
    }

    private fun byAnnotation(cls: Node, store: GraphStore): String? {
        val annotations = Attrs.annotations(cls).keys
        if ("org.springframework.stereotype.Repository" in annotations || isSpringData(cls, store)) return "repository"
        for ((layer, set) in ANNOTATIONS) if (annotations.any { it in set }) return layer
        return null
    }

    private fun isSpringData(cls: Node, store: GraphStore) =
        (store.edgesFrom(cls.id, EdgeKind.EXTENDS) + store.edgesFrom(cls.id, EdgeKind.IMPLEMENTS))
            .any { it.to.startsWith("org.springframework.data.") && it.to.endsWith("Repository") }

    private fun byName(cls: Node): String? {
        val name = cls.id.substringAfterLast('.').substringAfterLast('$')
        for ((layer, suffixes) in SUFFIXES) if (suffixes.any { name.endsWith(it) && name.length > it.length }) return layer
        return null
    }

    /** No methods beyond accessors and Object's: a data holder. */
    private fun isDataOnly(cls: Node, g: ClassGraph): Boolean {
        val members = g.outgoing[cls.id].orEmpty().filter { it.kind == EdgeKind.CONTAINS }.map { it.to }
        val methods = members.filter { '(' in it }.map { it.substringAfter('#').substringBefore('(') }
        return methods.all { it.startsWith("get") || it.startsWith("set") || it.startsWith("is") || it in OBJECT_METHODS || it.startsWith("<") }
    }

    private val OBJECT_METHODS = setOf("toString", "equals", "hashCode", "builder", "build", "of", "compareTo")
}
