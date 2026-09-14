package io.jirrafe.core.query

import io.jirrafe.core.model.Node

/** Reads the lines behind a node: repo file, extracted sources jar, or a lazy decompile. */
interface SourceReader {
    class Source(val file: String, val startLine: Int, val text: String, val decompiled: Boolean)

    /** `null` when nothing readable exists for the node (external stub, or decompiling is not allowed). */
    fun read(node: Node, contextLines: Int): Source?
}
