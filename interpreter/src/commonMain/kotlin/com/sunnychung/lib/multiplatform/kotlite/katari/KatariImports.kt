package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.model.*

data class KatariSourceRequest(
    val path: String,
    val importer: KatariSource?,
    val position: SourcePosition,
)

data class KatariSource(
    val filename: String,
    val code: String,
    val id: String = filename,
)

interface KatariSourceProvider {
    fun readSource(request: KatariSourceRequest): KatariSource
}

data object EmptyKatariSourceProvider : KatariSourceProvider {
    override fun readSource(request: KatariSourceRequest): KatariSource {
        throw IllegalArgumentException("${request.position} Katari source `${request.path}` is not available")
    }
}

data class KatariImportResolution(
    val script: ScriptNode,
    val nameAliases: Map<String, String> = emptyMap(),
    val scriptNamespaces: Map<String, Set<String>> = emptyMap(),
    val preprocessorInstructions: Map<String, List<KatariPreprocessorInstruction>> = emptyMap(),
)

fun resolveKatariImports(
    filename: String,
    script: ScriptNode,
    sourceProvider: KatariSourceProvider,
    preprocessorInstructions: List<KatariPreprocessorInstruction> = emptyList(),
): KatariImportResolution {
    val source = KatariSource(filename = filename, code = "", id = filename)
    return KatariImportResolver(sourceProvider).resolve(
        script = script,
        source = source,
        loadingStack = emptySet(),
        sourceInstructions = preprocessorInstructions,
    )
}

private class KatariImportResolver(
    private val sourceProvider: KatariSourceProvider,
) {
    fun resolve(
        script: ScriptNode,
        source: KatariSource,
        loadingStack: Set<String>,
        sourceInstructions: List<KatariPreprocessorInstruction>,
    ): KatariImportResolution {
        val outputNodes = mutableListOf<ASTNode>()
        val aliases = linkedMapOf<String, String>()
        val namespaces = linkedMapOf<String, MutableSet<String>>()
        val instructions = linkedMapOf(source.id to sourceInstructions)

        script.nodes.forEach { node ->
            when (node) {
                is KatariQualifiedImportNode -> {
                    if (!node.isWildcard) {
                        val importedName = node.path.last()
                        aliases[node.alias ?: importedName] = node.path.joinToString(".")
                    }
                }

                is KatariScriptImportNode -> {
                    val imported = readImportedScript(node, source, loadingStack)
                    aliases += imported.nameAliases
                    imported.preprocessorInstructions.forEach { (id, sourceDirectives) ->
                        instructions[id] = sourceDirectives
                    }
                    imported.scriptNamespaces.forEach { (name, members) ->
                        namespaces.getOrPut(name) { linkedSetOf() } += members
                    }

                    val importedNodes = imported.script.nodes.filterNot { it is KatariImportNode }
                    val alias = node.alias
                    if (alias == null) {
                        outputNodes += importedNodes
                    } else {
                        val memberNames = importedNodes.topLevelImportMemberNames()
                        namespaces.getOrPut(alias) { linkedSetOf() } += memberNames
                        outputNodes += importedNodes.map { it.withKatariNamespace(alias, memberNames, isTopLevel = true) }
                    }
                }

                else -> outputNodes += node
            }
        }
        return KatariImportResolution(
            script = ScriptNode(position = script.position, nodes = outputNodes),
            nameAliases = aliases,
            scriptNamespaces = namespaces.mapValues { it.value.toSet() },
            preprocessorInstructions = instructions,
        )
    }

    private fun readImportedScript(
        node: KatariScriptImportNode,
        importer: KatariSource,
        loadingStack: Set<String>,
    ): KatariImportResolution {
        val source = sourceProvider.readSource(KatariSourceRequest(node.path, importer, node.position))

        require(source.id !in loadingStack) {
            "${node.position} Circular Katari import detected for `${source.id}`"
        }
        val preprocessed = preprocessKatariSource(source.filename, source.code)
        val importedScript = KatariParser(
            Lexer(filename = source.filename, code = preprocessed.code, isParseSingleQuotedString = true)
        ).narrativeScript()
        return resolve(importedScript, source, loadingStack + source.id, preprocessed.instructions)
    }

}

private fun List<ASTNode>.topLevelImportMemberNames(): Set<String> {
    return mapNotNullTo(linkedSetOf()) { node ->
        when (node) {
            is FunctionDeclarationNode -> node.name
            is PropertyDeclarationNode -> node.name
            else -> null
        }
    }
}

private fun ASTNode.withKatariNamespace(
    namespace: String,
    topLevelNames: Set<String>,
    isTopLevel: Boolean = false,
    shadowedNames: Set<String> = emptySet(),
): ASTNode {
    fun ASTNode.transform(shadowed: Set<String> = shadowedNames): ASTNode =
        withKatariNamespace(namespace, topLevelNames, isTopLevel = false, shadowedNames = shadowed)

    fun String.namespacedIfVisible(): String {
        return if (this in topLevelNames && this !in shadowedNames) "$namespace.$this" else this
    }

    return when (this) {
        is FunctionDeclarationNode -> copy(
            name = if (isTopLevel && name in topLevelNames) "$namespace.$name" else name,
            valueParameters = valueParameters.map { it.transformParameter(namespace, topLevelNames, shadowedNames) },
            body = body?.transformBlock(
                namespace = namespace,
                topLevelNames = topLevelNames,
                shadowedNames = shadowedNames + valueParameters.map { it.name } + setOfNotNull(receiver?.name),
            ),
        )

        is PropertyDeclarationNode -> copy(
            name = if (isTopLevel && name in topLevelNames) "$namespace.$name" else name,
            initialValue = initialValue?.transform(),
        )

        is BlockNode -> transformBlock(namespace, topLevelNames, shadowedNames)

        is VariableReferenceNode -> VariableReferenceNode(position, variableName.namespacedIfVisible())

        is FunctionCallNode -> copy(
            function = function.transform(),
            arguments = arguments.map { it.copy(value = it.value.transform()) },
        )

        is FunctionCallArgumentNode -> copy(value = value.transform())
        is BinaryOpNode -> copy(node1 = node1.transform(), node2 = node2.transform())
        is UnaryOpNode -> copy(node = node?.transform())
        is InfixFunctionCallNode -> copy(node1 = node1.transform(), node2 = node2.transform())
        is ElvisOpNode -> copy(primaryNode = primaryNode.transform(), fallbackNode = fallbackNode.transform())
        is AssignmentNode -> copy(subject = subject.transform(), value = value.transform())
        is NavigationNode -> copy(subject = subject.transform())
        is IndexOpNode -> IndexOpNode(position, subject.transform(), arguments.map { it.transform() })
        is IfNode -> copy(
            condition = condition.transform(),
            trueBlock = trueBlock?.transformBlock(namespace, topLevelNames, shadowedNames),
            falseBlock = falseBlock?.transformBlock(namespace, topLevelNames, shadowedNames),
        )
        is WhileNode -> copy(condition = condition.transform(), body = body?.transformBlock(namespace, topLevelNames, shadowedNames))
        is DoWhileNode -> copy(condition = condition.transform(), body = body?.transformBlock(namespace, topLevelNames, shadowedNames))
        is ForNode -> copy(
            subject = subject.transform(),
            body = body.transformBlock(namespace, topLevelNames, shadowedNames + variables.map { it.name }),
        )
        is ReturnNode -> copy(value = value?.transform())
        is ThrowNode -> copy(value = value.transform())
        is TryNode -> copy(
            mainBlock = mainBlock.transformBlock(namespace, topLevelNames, shadowedNames),
            catchBlocks = catchBlocks.map { catch ->
                catch.copy(block = catch.block.transformBlock(namespace, topLevelNames, shadowedNames + catch.valueName))
            },
            finallyBlock = finallyBlock?.transformBlock(namespace, topLevelNames, shadowedNames),
        )
        is WhenNode -> copy(
            subject = subject?.copy(value = subject.value.transform()),
            entries = entries.map { entry ->
                entry.copy(
                    conditions = entry.conditions.map { it.copy(expression = it.expression.transform()) },
                    body = entry.body.transformBlock(namespace, topLevelNames, shadowedNames),
                )
            },
        )
        is LambdaLiteralNode -> copy(
            declaredValueParameters = declaredValueParameters.map { it.transformParameter(namespace, topLevelNames, shadowedNames) },
            body = body.transformBlock(namespace, topLevelNames, shadowedNames + valueParameters.map { it.name }),
        )
        is XmlNodeLiteralNode -> copy(
            attributes = attributes.map { it.copy(value = it.value.transform()) },
            children = children.map { it.transform() },
        )
        is XmlAttributeLiteralNode -> copy(value = value.transform())
        is StructLiteralNode -> copy(entries = entries.map { it.transform() as StructEntryLiteralNode })
        is StructEntryLiteralNode -> copy(value = value.transform())
        is StructArrayLiteralNode -> copy(elements = elements.map { it.transform() })
        is StringNode -> StringNode(position, nodes.map { it.transform() })
        else -> this
    }
}

private fun FunctionValueParameterNode.transformParameter(
    namespace: String,
    topLevelNames: Set<String>,
    shadowedNames: Set<String>,
): FunctionValueParameterNode {
    return copy(
        defaultValue = defaultValue?.withKatariNamespace(namespace, topLevelNames, shadowedNames = shadowedNames),
    )
}

private fun BlockNode.transformBlock(
    namespace: String,
    topLevelNames: Set<String>,
    shadowedNames: Set<String>,
): BlockNode {
    var localShadowed = shadowedNames
    val transformed = statements.map { statement ->
        statement.withKatariNamespace(namespace, topLevelNames, shadowedNames = localShadowed).also {
            if (statement is PropertyDeclarationNode) {
                localShadowed += statement.name
            }
        }
    }
    return copy(statements = transformed)
}
