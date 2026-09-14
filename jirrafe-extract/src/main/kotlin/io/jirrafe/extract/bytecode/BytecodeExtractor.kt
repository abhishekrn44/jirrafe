package io.jirrafe.extract.bytecode

import io.jirrafe.core.model.Attrs
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.GraphSink
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Param
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * Bytecode tier: exact structure and call sites from `.class` files via ASM. Used for internal jars
 * and for modules whose source tier is unavailable (Kotlin, Scala, Groovy).
 *
 * Edges into the JDK are dropped; everything else that is not indexed becomes an EXTERNAL stub
 * later (see `GraphStore.createStubs`). Lambda bodies are folded into their enclosing method.
 */
class BytecodeExtractor(private val sink: GraphSink) {

    class Context(val origin: Origin, val module: String?, val artifact: String?, val owner: String) {
        internal val packages = HashSet<String>()
        var classes = 0
            internal set
    }

    fun extractJar(jar: Path, ctx: Context): Int {
        JarFile(jar.toFile()).use { jf ->
            for (e in jf.entries()) {
                if (!e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                jf.getInputStream(e).use { extractClass(it.readAllBytes(), jar.toString(), ctx) }
            }
        }
        return ctx.classes
    }

    fun extractDir(dir: Path, ctx: Context): Int {
        Files.walk(dir).use { paths ->
            paths.filter { it.toString().endsWith(".class") }.sorted()
                .forEach { extractClass(Files.readAllBytes(it), it.toString(), ctx) }
        }
        return ctx.classes
    }

    fun extractClass(bytes: ByteArray, file: String, ctx: Context) {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES)
        if (cn.name.endsWith("module-info") || cn.name.endsWith("package-info")) return

        val cls = cn.name.replace('/', '.')
        val pkg = cls.substringBeforeLast('.', "")
        if (pkg.isNotEmpty() && ctx.packages.add(pkg)) {
            sink.node(Node(pkg, NodeKind.PACKAGE, pkg, ctx.origin, module = ctx.module, artifact = ctx.artifact))
            sink.edge(Edge(ctx.owner, pkg, EdgeKind.CONTAINS, Resolution.EXACT))
        }

        val kind = classKind(cn)
        val attrs = LinkedHashMap<String, String>()
        cn.sourceFile?.let { attrs["sourceFile"] = it }
        cn.signature?.let { attrs["genericSignature"] = it }
        annotationsJson(cn.visibleAnnotations, cn.invisibleAnnotations)?.let { attrs["annotations"] = it }
        sink.node(
            Node(
                cls, kind, cls, ctx.origin, signature = classSignature(cn, kind), file = file, sha = sha1(bytes),
                module = ctx.module, artifact = ctx.artifact, attrs = attrs,
            )
        )
        val outer = cn.innerClasses.firstOrNull { it.name == cn.name }?.outerName ?: cn.outerClass
        sink.edge(Edge(outer?.replace('/', '.') ?: pkg.ifEmpty { ctx.owner }, cls, EdgeKind.CONTAINS, Resolution.EXACT))
        cn.superName?.let { if (it != "java/lang/Object") typeEdge(cls, it, EdgeKind.EXTENDS) }
        cn.interfaces.forEach { typeEdge(cls, it, EdgeKind.IMPLEMENTS) }
        annotationEdges(cls, cn.visibleAnnotations, cn.invisibleAnnotations)

        val usedTypes = HashSet<String>()
        for (f in cn.fields) {
            if (f.access and Opcodes.ACC_SYNTHETIC != 0) continue
            val type = Type.getType(f.desc)
            val id = "$cls#${f.name}"
            val fattrs = annotationsJson(f.visibleAnnotations, f.invisibleAnnotations)?.let { mapOf("annotations" to it) } ?: emptyMap()
            sink.node(
                Node(
                    id, NodeKind.FIELD, id, ctx.origin, signature = "${modifiers(f.access)}${f.name}: ${type.className}",
                    file = file, module = ctx.module, artifact = ctx.artifact, attrs = fattrs,
                )
            )
            sink.edge(Edge(cls, id, EdgeKind.CONTAINS, Resolution.EXACT))
            annotationEdges(id, f.visibleAnnotations, f.invisibleAnnotations)
            useType(cls, type, usedTypes)
        }

        val methods = Methods(cn)
        for (m in cn.methods) {
            if (m.access and Opcodes.ACC_BRIDGE != 0 || methods.isFoldedLambda(m)) continue
            val id = methodId(cls, m.name, m.desc)
            val body = methods.body(m)
            val lines = m.instructions.filterIsInstance<LineNumberNode>().map { it.line }
            val mattrs = LinkedHashMap<String, String>()
            annotationsJson(m.visibleAnnotations, m.invisibleAnnotations)?.let { mattrs["annotations"] = it }
            Attrs.encodeStrings(body.strings)?.let { mattrs[Attrs.STRINGS] = it }
            val params = Type.getArgumentTypes(m.desc).mapIndexed { i, t ->
                Param(
                    m.parameters?.getOrNull(i)?.name ?: "arg$i", t.className,
                    annotationsMap(m.visibleParameterAnnotations?.getOrNull(i), m.invisibleParameterAnnotations?.getOrNull(i)),
                )
            }
            Attrs.encodeParams(params)?.let { mattrs[Attrs.PARAMS] = it }
            sink.node(
                Node(
                    id, if (m.name == "<init>") NodeKind.CONSTRUCTOR else NodeKind.METHOD, id, ctx.origin,
                    signature = methodSignature(m), file = file,
                    startLine = lines.minOrNull(), endLine = lines.maxOrNull(),
                    module = ctx.module, artifact = ctx.artifact, attrs = mattrs,
                )
            )
            sink.edge(Edge(cls, id, EdgeKind.CONTAINS, Resolution.EXACT))
            annotationEdges(id, m.visibleAnnotations, m.invisibleAnnotations)
            body.calls.forEach { sink.edge(Edge(id, it, EdgeKind.CALLS, Resolution.EXACT)) }
            body.reads.forEach { sink.edge(Edge(id, it, EdgeKind.READS_FIELD, Resolution.EXACT)) }
            body.writes.forEach { sink.edge(Edge(id, it, EdgeKind.WRITES_FIELD, Resolution.EXACT)) }
            Type.getArgumentTypes(m.desc).forEach { useType(cls, it, usedTypes) }
            useType(cls, Type.getReturnType(m.desc), usedTypes)
        }
        ctx.classes++
    }

    private class Body {
        val calls = LinkedHashSet<String>()
        val reads = LinkedHashSet<String>()
        val writes = LinkedHashSet<String>()
        val strings = ArrayList<String>()
    }

    /** Scans every method once, attributing lambda bodies to the method that creates the lambda. */
    private inner class Methods(private val cn: ClassNode) {
        private val byKey = cn.methods.associateBy { it.name + it.desc }
        private val lambdaOwner = HashMap<String, MethodNode>()
        private val bodies = HashMap<MethodNode, Body>()

        init {
            for (m in cn.methods) for (insn in m.instructions) {
                if (insn !is InvokeDynamicInsnNode) continue
                for (arg in insn.bsmArgs) {
                    if (arg is Handle && arg.owner == cn.name && byKey[arg.name + arg.desc]?.let(::isLambda) == true) {
                        lambdaOwner.putIfAbsent(arg.name + arg.desc, m)
                    }
                }
            }
            for (m in cn.methods) scan(m, bodies.getOrPut(effective(m)) { Body() })
        }

        fun isFoldedLambda(m: MethodNode) = isLambda(m) && lambdaOwner.containsKey(m.name + m.desc)

        fun body(m: MethodNode): Body = bodies[m] ?: Body()

        private fun isLambda(m: MethodNode) =
            m.access and Opcodes.ACC_SYNTHETIC != 0 && (m.name.startsWith("lambda\$") || m.name.contains("\$lambda\$"))

        private fun effective(m: MethodNode): MethodNode {
            var cur = m
            repeat(16) {
                if (!isLambda(cur)) return cur
                cur = lambdaOwner[cur.name + cur.desc] ?: return cur
            }
            return cur
        }

        private fun scan(m: MethodNode, body: Body) {
            for (insn in m.instructions) when (insn) {
                is MethodInsnNode -> {
                    val owner = Type.getObjectType(insn.owner).className
                    if (!isJdk(owner)) body.calls += methodId(owner, insn.name, insn.desc)
                }
                is FieldInsnNode -> {
                    val owner = Type.getObjectType(insn.owner).className
                    if (!isJdk(owner)) {
                        val id = "$owner#${insn.name}"
                        if (insn.opcode == Opcodes.GETFIELD || insn.opcode == Opcodes.GETSTATIC) body.reads += id else body.writes += id
                    }
                }
                is LdcInsnNode -> (insn.cst as? String)?.let { addString(body, it) }
                is InvokeDynamicInsnNode -> {
                    if (insn.bsm.name == "makeConcatWithConstants") {
                        (insn.bsmArgs.firstOrNull() as? String)?.split('\u0001', '\u0002')?.forEach { part ->
                            if (part.isNotBlank()) addString(body, part)
                        }
                    }
                    for (arg in insn.bsmArgs) {
                        if (arg !is Handle || arg.tag < Opcodes.H_INVOKEVIRTUAL) continue
                        if (arg.owner == cn.name && byKey[arg.name + arg.desc]?.let(::isLambda) == true) continue
                        val owner = Type.getObjectType(arg.owner).className
                        if (!isJdk(owner)) body.calls += methodId(owner, arg.name, arg.desc)
                    }
                }
            }
        }

        private fun addString(body: Body, s: String) {
            if (body.strings.size < 50 && s.length <= 500) body.strings += s
        }
    }

    private fun typeEdge(from: String, internalName: String, kind: EdgeKind) {
        val t = internalName.replace('/', '.')
        if (!isJdk(t)) sink.edge(Edge(from, t, kind, Resolution.EXACT))
    }

    private fun useType(cls: String, type: Type, seen: MutableSet<String>) {
        val t = if (type.sort == Type.ARRAY) type.elementType else type
        if (t.sort != Type.OBJECT) return
        val name = t.className
        if (name != cls && !isJdk(name) && seen.add(name)) sink.edge(Edge(cls, name, EdgeKind.USES_TYPE, Resolution.EXACT))
    }

    private fun annotationEdges(from: String, vararg lists: List<AnnotationNode>?) {
        for (a in lists.asSequence().filterNotNull().flatten()) {
            typeEdge(from, Type.getType(a.desc).internalName, EdgeKind.ANNOTATED_WITH)
        }
    }

    companion object {

        fun isJdk(className: String) =
            className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.") || className.startsWith("com.sun.")

        fun methodId(owner: String, name: String, desc: String): String =
            "$owner#$name(${Type.getArgumentTypes(desc).joinToString(",") { it.className }})"

        private fun classKind(cn: ClassNode): NodeKind = when {
            cn.access and Opcodes.ACC_ANNOTATION != 0 -> NodeKind.ANNOTATION
            cn.access and Opcodes.ACC_ENUM != 0 -> NodeKind.ENUM
            cn.access and Opcodes.ACC_INTERFACE != 0 -> NodeKind.INTERFACE
            cn.superName == "java/lang/Record" -> NodeKind.RECORD
            else -> NodeKind.CLASS
        }

        private fun classSignature(cn: ClassNode, kind: NodeKind): String {
            val keyword = when (kind) {
                NodeKind.ANNOTATION -> "@interface"
                NodeKind.ENUM -> "enum"
                NodeKind.INTERFACE -> "interface"
                NodeKind.RECORD -> "record"
                else -> "class"
            }
            val access = if (kind == NodeKind.CLASS) cn.access else cn.access and Opcodes.ACC_ABSTRACT.inv()
            val sb = StringBuilder(modifiers(access))
            sb.append(keyword).append(' ').append(cn.name.substringAfterLast('/'))
            cn.superName?.takeIf { it != "java/lang/Object" && kind == NodeKind.CLASS }
                ?.let { sb.append(" extends ").append(it.substringAfterLast('/')) }
            if (cn.interfaces.isNotEmpty()) {
                sb.append(if (kind == NodeKind.INTERFACE) " extends " else " implements ")
                sb.append(cn.interfaces.joinToString(", ") { it.substringAfterLast('/') })
            }
            return sb.toString()
        }

        private fun methodSignature(m: MethodNode): String {
            val args = Type.getArgumentTypes(m.desc).joinToString(", ") { it.className }
            val ret = Type.getReturnType(m.desc).className
            return "${modifiers(m.access)}${m.name}($args)" + if (m.name == "<init>") "" else ": $ret"
        }

        private fun modifiers(access: Int): String {
            val sb = StringBuilder()
            if (access and Opcodes.ACC_PUBLIC != 0) sb.append("public ")
            if (access and Opcodes.ACC_PROTECTED != 0) sb.append("protected ")
            if (access and Opcodes.ACC_PRIVATE != 0) sb.append("private ")
            if (access and Opcodes.ACC_STATIC != 0) sb.append("static ")
            if (access and Opcodes.ACC_ABSTRACT != 0) sb.append("abstract ")
            if (access and Opcodes.ACC_FINAL != 0) sb.append("final ")
            return sb.toString()
        }

        /** `{"com.acme.Route": {"value": "/orders", "method": "GET"}}`; absent when there are no annotations. */
        private fun annotationsJson(vararg lists: List<AnnotationNode>?): String? = Attrs.encodeAnnotations(annotationsMap(*lists))

        private fun annotationsMap(vararg lists: List<AnnotationNode>?): Map<String, Map<String, String>> {
            val map = LinkedHashMap<String, Map<String, String>>()
            for (a in lists.asSequence().filterNotNull().flatten()) {
                val values = LinkedHashMap<String, String>()
                val vs = a.values ?: emptyList()
                for (i in 0 until vs.size - 1 step 2) values[vs[i] as String] = annotationValue(vs[i + 1])
                map[Type.getType(a.desc).className] = values
            }
            return map
        }

        private fun annotationValue(v: Any?): String = when (v) {
            is List<*> -> v.joinToString(",") { annotationValue(it) }
            is Array<*> -> v.last().toString() // enum: [descriptor, constant]
            is Type -> v.className
            is AnnotationNode -> "@" + Type.getType(v.desc).className
            else -> v.toString()
        }

        private fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
