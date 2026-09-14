package io.jirrafe.core.plugin

import io.jirrafe.core.manifest.Manifest
import io.jirrafe.core.store.GraphStore

/**
 * A framework plugin runs after extraction and adds framework semantics on top of the code graph:
 * beans, routes, config keys, topics, jobs, findings. Discovered with `ServiceLoader`; register
 * implementations in `META-INF/services/io.jirrafe.core.plugin.FrameworkPlugin`.
 *
 * Plugins read the graph through [IndexContext.store] (nodes carry annotation values in
 * `attrs.annotations`, parameters in `attrs.params`, string constants in `attrs.strings`) and
 * write with [GraphStore.node] / [GraphStore.edge]. The nodes a plugin produces are of the kinds
 * BEAN, HTTP_ROUTE, CONFIG_KEY, MESSAGE_TOPIC, SCHEDULED_JOB and FINDING; those are cleared before
 * every plugin run, so a plugin must be a pure function of the code graph.
 */
interface FrameworkPlugin {
    val id: String
    fun contribute(ctx: IndexContext)
}

interface IndexContext {
    val manifest: Manifest
    val store: GraphStore
    fun log(message: String)
}
