package io.jirrafe.extract.source

import com.sun.source.tree.AssignmentTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.CompoundAssignmentTree
import com.sun.source.tree.ExpressionTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MemberReferenceTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.ModifiersTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.Tree
import com.sun.source.tree.UnaryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import io.jirrafe.core.model.Attrs
import io.jirrafe.core.model.Edge
import io.jirrafe.core.model.EdgeKind
import io.jirrafe.core.model.GraphSink
import io.jirrafe.core.model.Node
import io.jirrafe.core.model.NodeKind
import io.jirrafe.core.model.Param
import io.jirrafe.core.model.Origin
import io.jirrafe.core.model.Resolution
import io.jirrafe.extract.bytecode.BytecodeExtractor.Companion.isJdk
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.NestingKind
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.ArrayType
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/**
 * Source tier on the javac Compiler Tree API: parse + attribute (never generate), with the full
 * classpath so overloads, generics and inherited members resolve exactly. Annotation processors
 * (Lombok, MapStruct...) run in memory, so their members are visible.
 *
 * A call edge is emitted only when the invocation and every argument have a non-ERROR type;
 * anything else is left to the bytecode tier, which is extracted first. Node ids match the
 * bytecode tier's, so source nodes replace bytecode nodes for the same declarations.
 */
class SourceExtractor(private val sink: GraphSink) {

    class Options(
        /** FILE ids are paths relative to this directory. */
        val root: Path,
        val classpath: List<Path>,
        val processorPath: List<Path> = emptyList(),
        /** Where processor-generated sources are written and kept, so they can be read later. */
        val generatedDir: Path,
        val release: Int? = null,
        val origin: Origin,
        val module: String? = null,
        val artifact: String? = null,
        val owner: String,
        val test: Boolean = false,
    )

    data class Result(val files: Int, val classes: Int, val errors: List<String>)

    fun extract(files: List<Path>, opt: Options): Result {
        if (files.isEmpty()) return Result(0, 0, emptyList())
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("No system Java compiler: run jirrafe on a JDK, not a JRE")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fm ->
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, opt.classpath.filter { Files.exists(it) })
            Files.createDirectories(opt.generatedDir)
            fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, listOf(opt.generatedDir))
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, listOf(opt.generatedDir))
            if (opt.processorPath.isNotEmpty()) {
                fm.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, opt.processorPath)
            }
            val options = mutableListOf("--should-stop=ifError=FLOW", "-Xlint:none", "-nowarn", "-Xmaxerrs", "10000")
            opt.release?.let { options += listOf("--release", minOf(it, Runtime.version().feature()).toString()) }

            val task = compiler.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(files)) as JavacTask
            val units = LinkedHashSet(task.parse().toList())
            val analyzed = task.analyze()
            val trees = Trees.instance(task)
            for (e in analyzed) trees.getPath(e)?.compilationUnit?.let { units += it } // generated sources
            val scanner = Scanner(task, trees, opt)
            for (cu in units) scanner.scanUnit(cu)
            val errors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
                .map { "${it.source?.name}:${it.lineNumber}: ${it.getMessage(null)}" }
            return Result(units.size, scanner.classes, errors)
        }
    }

    private inner class Scanner(task: JavacTask, private val trees: Trees, private val opt: Options) :
        TreePathScanner<Unit?, Unit?>() {
        private val elements = task.elements
        private val types = task.types
        private val positions = trees.sourcePositions
        var classes = 0
            private set

        private lateinit var cu: CompilationUnitTree
        private lateinit var lineMap: LineMap
        private lateinit var filePath: String
        private lateinit var fileId: String
        private lateinit var fileSha: String
        private var packageName = ""
        private var generated = false
        private val packages = HashSet<String>()

        /** Enclosing declaration ids, innermost last: a method id inside a body, else a class id. */
        private val owners = ArrayDeque<String>()
        private val strings = HashMap<String, MutableList<String>>()
        private val usedTypes = HashMap<String, MutableSet<String>>()

        fun scanUnit(unit: CompilationUnitTree) {
            cu = unit
            lineMap = unit.lineMap
            val path = Paths.get(unit.sourceFile.toUri()).toAbsolutePath()
            filePath = path.toString()
            generated = path.startsWith(opt.generatedDir.toAbsolutePath())
            val rel = (if (path.startsWith(opt.root)) opt.root.relativize(path) else path).toString().replace('\\', '/')
            fileId = "file:$rel"
            fileSha = sha1(Files.readAllBytes(path))
            packageName = unit.packageName?.toString() ?: ""

            sink.node(
                Node(fileId, NodeKind.FILE, rel, opt.origin, file = filePath, sha = fileSha,
                    module = opt.module, artifact = opt.artifact, attrs = flags()),
                replace = true,
            )
            if (packageName.isNotEmpty() && packages.add(packageName)) {
                sink.node(Node(packageName, NodeKind.PACKAGE, packageName, opt.origin))
                sink.edge(Edge(opt.owner, packageName, EdgeKind.CONTAINS, Resolution.EXACT))
            }
            val unitPath = TreePath(unit)
            for (imp in unit.imports) {
                val e = trees.getElement(TreePath(TreePath(unitPath, imp), imp.qualifiedIdentifier))
                val type = e as? TypeElement ?: e?.enclosingElement as? TypeElement ?: continue
                typeEdge(fileId, type, EdgeKind.IMPORTS)
            }
            scan(unitPath, null)
        }

        override fun visitClass(node: ClassTree, p: Unit?): Unit? {
            val e = trees.getElement(currentPath) as? TypeElement ?: return null
            val id = binaryName(e)
            val kind = when (e.kind) {
                ElementKind.INTERFACE -> NodeKind.INTERFACE
                ElementKind.ENUM -> NodeKind.ENUM
                ElementKind.RECORD -> NodeKind.RECORD
                ElementKind.ANNOTATION_TYPE -> NodeKind.ANNOTATION
                else -> NodeKind.CLASS
            }
            val enclosingType = generateSequence(e.enclosingElement) { it.enclosingElement }.firstOrNull { it is TypeElement } as TypeElement?
            sink.edge(Edge(enclosingType?.let { binaryName(it) } ?: packageName.ifEmpty { opt.owner }, id, EdgeKind.CONTAINS, Resolution.EXACT))
            sink.edge(Edge(fileId, id, EdgeKind.CONTAINS, Resolution.EXACT))
            e.superclass.takeIf { it.kind == TypeKind.DECLARED }?.let { (it as DeclaredType).asElement() as TypeElement }?.let { s ->
                if (s.qualifiedName.toString() != "java.lang.Object") typeEdge(id, s, EdgeKind.EXTENDS)
            }
            for (i in e.interfaces) if (i.kind == TypeKind.DECLARED) typeEdge(id, (i as DeclaredType).asElement() as TypeElement, EdgeKind.IMPLEMENTS)
            annotationEdges(id, e)

            owners.addLast(id)
            super.visitClass(node, p)
            owners.removeLast()

            val attrs = flags()
            cu.sourceFile?.name?.let { attrs["sourceFile"] = it.substringAfterLast('/').substringAfterLast('\\') }
            annotationsJson(e, node.modifiers)?.let { attrs[Attrs.ANNOTATIONS] = it }
            strings.remove(id)?.let { l -> Attrs.encodeStrings(l)?.let { attrs[Attrs.STRINGS] = it } }
            val supertypes = (listOfNotNull(e.superclass.takeIf { it.kind == TypeKind.DECLARED }) + e.interfaces)
            if (supertypes.none { it.kind == TypeKind.ERROR } && supertypes.any { it.toString().contains("<") }) {
                attrs[Attrs.SUPERTYPES] = supertypes.joinToString(";") { it.toString() }
            }
            sink.node(
                Node(
                    id, kind, id, opt.origin, signature = classSignature(e, kind), file = filePath,
                    startLine = line(positions.getStartPosition(cu, node)), endLine = line(positions.getEndPosition(cu, node)),
                    doc = doc(e), sha = fileSha, module = opt.module, artifact = opt.artifact, attrs = attrs,
                ),
                replace = true,
            )
            classes++
            return null
        }

        override fun visitMethod(node: MethodTree, p: Unit?): Unit? {
            val e = trees.getElement(currentPath) as? ExecutableElement ?: return null
            // A member whose types did not resolve would get an id the bytecode tier does not use; leave it to bytecode.
            if (e.parameters.any { hasError(it.asType()) } || hasError(e.returnType)) return null
            val cls = owners.last()
            val id = methodId(e)
            owners.addLast(id)
            super.visitMethod(node, p)
            owners.removeLast()

            sink.edge(Edge(cls, id, EdgeKind.CONTAINS, Resolution.EXACT))
            annotationEdges(id, e)
            overrides(e, id)
            e.parameters.forEach { useType(cls, it.asType()) }
            useType(cls, e.returnType)
            val attrs = flags()
            annotationsJson(e, node.modifiers)?.let { attrs[Attrs.ANNOTATIONS] = it }
            strings.remove(id)?.let { l -> Attrs.encodeStrings(l)?.let { attrs[Attrs.STRINGS] = it } }
            val params = e.parameters.mapIndexed { i, p ->
                Param(p.simpleName.toString(), p.asType().toString(), annotationsMap(p, node.parameters.getOrNull(i)?.modifiers))
            }
            Attrs.encodeParams(params)?.let { attrs[Attrs.PARAMS] = it }
            sink.node(
                Node(
                    id, if (e.kind == ElementKind.CONSTRUCTOR) NodeKind.CONSTRUCTOR else NodeKind.METHOD, id, opt.origin,
                    signature = methodSignature(e), file = filePath,
                    startLine = line(positions.getStartPosition(cu, node)), endLine = line(positions.getEndPosition(cu, node)),
                    doc = doc(e), sha = fileSha, module = opt.module, artifact = opt.artifact, attrs = attrs,
                ),
                replace = true,
            )
            return null
        }

        override fun visitVariable(node: VariableTree, p: Unit?): Unit? {
            val e = trees.getElement(currentPath) as? VariableElement
            if (e != null && (e.kind == ElementKind.FIELD || e.kind == ElementKind.ENUM_CONSTANT) && owners.isNotEmpty()) {
                val cls = owners.last()
                val id = "$cls#${e.simpleName}"
                val attrs = flags()
                annotationsJson(e, node.modifiers)?.let { attrs[Attrs.ANNOTATIONS] = it }
                sink.node(
                    Node(
                        id, NodeKind.FIELD, id, opt.origin,
                        signature = "${modifiers(e.modifiers)}${e.simpleName}: ${typeName(e.asType())}", file = filePath,
                        startLine = line(positions.getStartPosition(cu, node)), endLine = line(positions.getEndPosition(cu, node)),
                        doc = doc(e), sha = fileSha, module = opt.module, artifact = opt.artifact, attrs = attrs,
                    ),
                    replace = true,
                )
                sink.edge(Edge(cls, id, EdgeKind.CONTAINS, Resolution.EXACT))
                annotationEdges(id, e)
                useType(cls, e.asType())
            }
            return super.visitVariable(node, p)
        }

        override fun visitMethodInvocation(node: MethodInvocationTree, p: Unit?): Unit? {
            val e = trees.getElement(TreePath(currentPath, node.methodSelect)) as? ExecutableElement
            if (e != null && !isError(trees.getTypeMirror(currentPath)) && argumentsResolved(node.arguments)) call(e)
            return super.visitMethodInvocation(node, p)
        }

        override fun visitNewClass(node: NewClassTree, p: Unit?): Unit? {
            val e = trees.getElement(currentPath) as? ExecutableElement
            if (e != null && argumentsResolved(node.arguments)) call(e)
            return super.visitNewClass(node, p)
        }

        override fun visitMemberReference(node: MemberReferenceTree, p: Unit?): Unit? {
            (trees.getElement(currentPath) as? ExecutableElement)?.let { call(it) }
            return super.visitMemberReference(node, p)
        }

        override fun visitIdentifier(node: IdentifierTree, p: Unit?): Unit? {
            fieldAccess(node)
            return super.visitIdentifier(node, p)
        }

        override fun visitMemberSelect(node: MemberSelectTree, p: Unit?): Unit? {
            fieldAccess(node)
            return super.visitMemberSelect(node, p)
        }

        override fun visitLiteral(node: LiteralTree, p: Unit?): Unit? {
            val s = node.value as? String
            val owner = owners.lastOrNull()
            if (s != null && owner != null) {
                val list = strings.getOrPut(owner) { ArrayList() }
                if (list.size < 50 && s.length <= 500) list += s // same cap as the bytecode tier
            }
            return null
        }

        private fun call(e: ExecutableElement) {
            val owner = e.enclosingElement as? TypeElement ?: return
            val from = owners.lastOrNull() ?: return
            if (isJdk(binaryName(owner))) return
            sink.edge(Edge(from, methodId(e), EdgeKind.CALLS, Resolution.EXACT))
        }

        private fun fieldAccess(node: ExpressionTree) {
            val from = owners.lastOrNull() ?: return
            val e = trees.getElement(currentPath) as? VariableElement ?: return
            if (e.kind != ElementKind.FIELD && e.kind != ElementKind.ENUM_CONSTANT) return
            val owner = e.enclosingElement as? TypeElement ?: return
            val ownerId = binaryName(owner)
            if (isJdk(ownerId)) return
            val id = "$ownerId#${e.simpleName}"
            val parent = currentPath.parentPath?.leaf
            val assigned = parent is AssignmentTree && parent.variable === node
            val updated = (parent is CompoundAssignmentTree && parent.variable === node) ||
                (parent is UnaryTree && parent.kind in INCREMENTS)
            if (assigned || updated) sink.edge(Edge(from, id, EdgeKind.WRITES_FIELD, Resolution.EXACT))
            if (!assigned) sink.edge(Edge(from, id, EdgeKind.READS_FIELD, Resolution.EXACT))
        }

        private fun argumentsResolved(args: List<ExpressionTree>) =
            args.none { isError(trees.getTypeMirror(TreePath(currentPath, it))) }

        private fun isError(t: TypeMirror?) = t == null || t.kind == TypeKind.ERROR

        private fun overrides(e: ExecutableElement, id: String) {
            if (e.kind != ElementKind.METHOD || Modifier.STATIC in e.modifiers) return
            val owner = e.enclosingElement as? TypeElement ?: return
            val seen = HashSet<String>()
            val queue = ArrayDeque(types.directSupertypes(owner.asType()))
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst() as? DeclaredType ?: continue
                val st = t.asElement() as? TypeElement ?: continue
                val name = binaryName(st)
                if (isJdk(name) || !seen.add(name)) continue
                for (m in ElementFilter.methodsIn(st.enclosedElements)) {
                    if (elements.overrides(e, m, owner)) sink.edge(Edge(id, methodId(m), EdgeKind.OVERRIDES, Resolution.EXACT))
                }
                queue += types.directSupertypes(t)
            }
        }

        private fun useType(cls: String, t: TypeMirror) {
            val erased = types.erasure(t)
            val base = if (erased.kind == TypeKind.ARRAY) types.erasure((erased as ArrayType).componentType) else erased
            if (base.kind != TypeKind.DECLARED) return
            val te = (base as DeclaredType).asElement() as? TypeElement ?: return
            val name = binaryName(te)
            if (name != cls && !isJdk(name) && usedTypes.getOrPut(cls) { HashSet() }.add(name)) {
                sink.edge(Edge(cls, name, EdgeKind.USES_TYPE, Resolution.EXACT))
            }
        }

        private fun typeEdge(from: String, type: TypeElement, kind: EdgeKind) {
            val name = binaryName(type)
            if (!isJdk(name)) sink.edge(Edge(from, name, kind, Resolution.EXACT))
        }

        private fun annotationEdges(from: String, e: Element) {
            for (a in e.annotationMirrors) {
                if (a.annotationType.kind == TypeKind.DECLARED) typeEdge(from, a.annotationType.asElement() as TypeElement, EdgeKind.ANNOTATED_WITH)
            }
        }

        private fun hasError(t: TypeMirror): Boolean = when (t.kind) {
            TypeKind.ERROR -> true
            TypeKind.ARRAY -> hasError((t as ArrayType).componentType)
            TypeKind.DECLARED -> (t as DeclaredType).typeArguments.any { hasError(it) }
            else -> false
        }

        private fun annotationsJson(e: Element, modifiers: ModifiersTree?): String? = Attrs.encodeAnnotations(annotationsMap(e, modifiers))

        /**
         * Empty when the declaration has annotations javac could not resolve (it drops them from the
         * element, so the tree is the reference), so the bytecode tier's exact annotations are kept.
         */
        private fun annotationsMap(e: Element, modifiers: ModifiersTree?): Map<String, Map<String, String>> {
            val map = LinkedHashMap<String, Map<String, String>>()
            if (modifiers != null && modifiers.annotations.size != e.annotationMirrors.size) return map
            if (e.annotationMirrors.any { it.annotationType.kind != TypeKind.DECLARED }) return map
            for (a in e.annotationMirrors) {
                map[binaryName(a.annotationType.asElement() as TypeElement)] =
                    a.elementValues.entries.associate { (k, v) -> k.simpleName.toString() to annotationValue(v.value) }
            }
            return map
        }

        private fun annotationValue(v: Any?): String = when (v) {
            is List<*> -> v.joinToString(",") { annotationValue((it as? AnnotationValue)?.value ?: it) }
            is VariableElement -> v.simpleName.toString()
            is TypeMirror -> typeName(v)
            is AnnotationMirror -> "@" + binaryName(v.annotationType.asElement() as TypeElement)
            else -> v.toString()
        }

        /** Same shape as the bytecode tier: `Owner#name(erasedArg,erasedArg)` with binary names. */
        private fun methodId(e: ExecutableElement): String {
            val owner = e.enclosingElement as TypeElement
            val params = ArrayList<String>()
            if (e.kind == ElementKind.CONSTRUCTOR) {
                when {
                    owner.kind == ElementKind.ENUM -> params += listOf("java.lang.String", "int")
                    owner.nestingKind != NestingKind.TOP_LEVEL && Modifier.STATIC !in owner.modifiers && !inStaticContext(owner) ->
                        (generateSequence(owner.enclosingElement) { it.enclosingElement }.firstOrNull { it is TypeElement } as TypeElement?)
                            ?.let { params += binaryName(it) }
                }
            }
            e.parameters.forEach { params += typeName(it.asType()) }
            return "${binaryName(owner)}#${e.simpleName}(${params.joinToString(",")})"
        }

        private fun inStaticContext(owner: TypeElement): Boolean {
            if (owner.nestingKind == NestingKind.MEMBER) return false
            var el: Element? = owner.enclosingElement
            while (el != null && el !is TypeElement) {
                if (Modifier.STATIC in el.modifiers) return true
                el = el.enclosingElement
            }
            return false
        }

        private fun typeName(t: TypeMirror): String {
            val erased = types.erasure(t)
            return when (erased.kind) {
                TypeKind.ARRAY -> typeName((erased as ArrayType).componentType) + "[]"
                TypeKind.DECLARED -> binaryName((erased as DeclaredType).asElement() as TypeElement)
                else -> if (erased.kind.isPrimitive) erased.kind.name.lowercase() else erased.toString() // toString() prints `@Min(1L) int`
            }
        }

        private fun binaryName(e: TypeElement) = elements.getBinaryName(e).toString()

        private fun classSignature(e: TypeElement, kind: NodeKind): String {
            val keyword = when (kind) {
                NodeKind.ANNOTATION -> "@interface"
                NodeKind.ENUM -> "enum"
                NodeKind.INTERFACE -> "interface"
                NodeKind.RECORD -> "record"
                else -> "class"
            }
            val mods = if (kind == NodeKind.CLASS) e.modifiers else e.modifiers - Modifier.ABSTRACT
            val sb = StringBuilder(modifiers(mods)).append(keyword).append(' ')
            sb.append(e.simpleName.toString().ifEmpty { binaryName(e).substringAfterLast('$') })
            (e.superclass as? DeclaredType)?.asElement()?.takeIf { kind == NodeKind.CLASS }?.let {
                if ((it as TypeElement).qualifiedName.toString() != "java.lang.Object") sb.append(" extends ").append(it.simpleName)
            }
            if (e.interfaces.isNotEmpty()) {
                sb.append(if (kind == NodeKind.INTERFACE) " extends " else " implements ")
                sb.append(e.interfaces.joinToString(", ") { ((it as DeclaredType).asElement() as TypeElement).simpleName })
            }
            return sb.toString()
        }

        private fun methodSignature(e: ExecutableElement): String {
            val args = e.parameters.joinToString(", ") { typeName(it.asType()) }
            val head = "${modifiers(e.modifiers)}${e.simpleName}($args)"
            return if (e.kind == ElementKind.CONSTRUCTOR) head else "$head: ${typeName(e.returnType)}"
        }

        private fun modifiers(mods: Set<Modifier>): String {
            val sb = StringBuilder()
            for (m in ORDERED_MODIFIERS) if (m in mods) sb.append(m.toString()).append(' ')
            return sb.toString()
        }

        private fun doc(e: Element): String? = elements.getDocComment(e)?.trim()?.take(4000)?.ifEmpty { null }

        private fun line(pos: Long): Int? = if (pos < 0) null else lineMap.getLineNumber(pos).toInt()

        private fun flags(): LinkedHashMap<String, String> {
            val m = LinkedHashMap<String, String>()
            if (opt.test) m["test"] = "true"
            if (generated) m["generated"] = "true"
            return m
        }
    }

    companion object {
        private val ORDERED_MODIFIERS = listOf(
            Modifier.PUBLIC, Modifier.PROTECTED, Modifier.PRIVATE, Modifier.STATIC, Modifier.ABSTRACT, Modifier.FINAL,
        )
        private val INCREMENTS = setOf(
            Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT, Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT,
        )

        fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
