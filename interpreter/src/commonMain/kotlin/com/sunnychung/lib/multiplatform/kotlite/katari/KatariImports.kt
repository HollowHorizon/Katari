package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.model.ASTNode
import com.sunnychung.lib.multiplatform.kotlite.model.AssignmentNode
import com.sunnychung.lib.multiplatform.kotlite.model.BinaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.BlockNode
import com.sunnychung.lib.multiplatform.kotlite.model.ClassDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.DoWhileNode
import com.sunnychung.lib.multiplatform.kotlite.model.ElvisOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.ForNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallArgumentNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionValueParameterNode
import com.sunnychung.lib.multiplatform.kotlite.model.IfNode
import com.sunnychung.lib.multiplatform.kotlite.model.IndexOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.InfixFunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.KatariImportNode
import com.sunnychung.lib.multiplatform.kotlite.model.KatariQualifiedImportNode
import com.sunnychung.lib.multiplatform.kotlite.model.KatariScriptImportNode
import com.sunnychung.lib.multiplatform.kotlite.model.LambdaLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.NavigationNode
import com.sunnychung.lib.multiplatform.kotlite.model.PropertyDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.ReturnNode
import com.sunnychung.lib.multiplatform.kotlite.model.ScriptNode
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.StringNode
import com.sunnychung.lib.multiplatform.kotlite.model.ThrowNode
import com.sunnychung.lib.multiplatform.kotlite.model.TryNode
import com.sunnychung.lib.multiplatform.kotlite.model.TypeNode
import com.sunnychung.lib.multiplatform.kotlite.model.UnaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.VariableReferenceNode
import com.sunnychung.lib.multiplatform.kotlite.model.WhenConditionNode
import com.sunnychung.lib.multiplatform.kotlite.model.WhenEntryNode
import com.sunnychung.lib.multiplatform.kotlite.model.WhenNode
import com.sunnychung.lib.multiplatform.kotlite.model.WhileNode

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
)

fun resolveKatariImports(
    filename: String,
    script: ScriptNode,
    sourceProvider: KatariSourceProvider,
): KatariImportResolution {
    val source = KatariSource(filename = filename, code = "", id = filename)
    return KatariImportResolver(sourceProvider).resolve(script, source, emptySet())
}

private class KatariImportResolver(
    private val sourceProvider: KatariSourceProvider,
) {
    fun resolve(
        script: ScriptNode,
        source: KatariSource,
        loadingStack: Set<String>,
    ): KatariImportResolution {
        val outputNodes = mutableListOf<ASTNode>()
        val aliases = linkedMapOf<String, String>()
        val namespaces = linkedMapOf<String, MutableSet<String>>()
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
                    imported.scriptNamespaces.forEach { (name, functions) ->
                        namespaces.getOrPut(name) { linkedSetOf() } += functions
                    }
                    val declarations = imported.script.nodes.filterTopLevelImportDeclarations()
                    if (node.alias != null) {
                        val functionNames = declarations.filterIsInstance<FunctionDeclarationNode>().mapTo(linkedSetOf()) { it.name }
                        namespaces.getOrPut(node.alias) { linkedSetOf() } += functionNames
                        outputNodes += declarations.map { it.withKatariNamespace(node.alias, functionNames) }
                        if (node.isLoad) {
                            outputNodes += imported.script.nodes
                                .filterNot { it is KatariImportNode || it is FunctionDeclarationNode || it is ClassDeclarationNode }
                                .map { it.withKatariNamespace(node.alias, functionNames) }
                        }
                    } else if (node.isLoad) {
                        outputNodes += imported.script.nodes.filterNot { it is KatariImportNode }
                    } else {
                        outputNodes += declarations
                    }
                }
                else -> outputNodes += node
            }
        }
        return KatariImportResolution(
            script = ScriptNode(position = script.position, nodes = outputNodes),
            nameAliases = aliases,
            scriptNamespaces = namespaces.mapValues { it.value.toSet() },
        )
    }

    private fun readImportedScript(
        node: KatariScriptImportNode,
        importer: KatariSource,
        loadingStack: Set<String>,
    ): KatariImportResolution {
        val source = sourceProvider.readSource(KatariSourceRequest(node.path, importer, node.position))
        if (source.id in loadingStack) {
            require(!node.isLoad) {
                "${node.position} Circular Katari load detected for `${source.id}`"
            }
            return KatariImportResolution(
                script = ScriptNode(position = node.position, nodes = emptyList()),
            )
        }
        val importedScript = KatariParser(
            Lexer(filename = source.filename, code = source.code, isParseSingleQuotedString = true)
        ).narrativeScript()
        return resolve(importedScript, source, loadingStack + source.id)
    }
}

private fun List<ASTNode>.filterTopLevelImportDeclarations(): List<ASTNode> {
    return filter { it is FunctionDeclarationNode || it is ClassDeclarationNode }
}

private fun ASTNode.withKatariNamespace(namespace: String, functionNames: Set<String>): ASTNode {
    return transformKatariNode(functionNames) { name ->
        if (name in functionNames) "$namespace.$name" else name
    }.let { transformed ->
        if (transformed is FunctionDeclarationNode && transformed.name in functionNames) {
            transformed.copy(name = "$namespace.${transformed.name}")
        } else {
            transformed
        }
    }
}

private fun ASTNode.transformKatariNode(
    functionNames: Set<String>,
    mapFunctionName: (String) -> String,
): ASTNode {
    fun ASTNode.transform(): ASTNode = transformKatariNode(functionNames, mapFunctionName)
    return when (this) {
        is FunctionDeclarationNode -> copy(
            valueParameters = valueParameters.map { it.transformParameter(functionNames, mapFunctionName) },
            body = body?.transformBlock(functionNames, mapFunctionName),
        )
        is PropertyDeclarationNode -> copy(
            initialValue = initialValue?.transform(),
        )
        is BlockNode -> transformBlock(functionNames, mapFunctionName)
        is FunctionCallNode -> copy(
            function = when (function) {
                is VariableReferenceNode -> {
                    val name = function.variableName
                    if (name in functionNames) VariableReferenceNode(function.position, mapFunctionName(name)) else function
                }
                else -> function.transform()
            },
            arguments = arguments.map { it.copy(value = it.value.transform()) },
        )
        is FunctionCallArgumentNode -> copy(value = value.transform())
        is BinaryOpNode -> copy(node1 = node1.transform(), node2 = node2.transform())
        is UnaryOpNode -> copy(node = node?.transform())
        is InfixFunctionCallNode -> copy(node1 = node1.transform(), node2 = node2.transform())
        is ElvisOpNode -> copy(primaryNode = primaryNode.transform(), fallbackNode = fallbackNode.transform())
        is AssignmentNode -> AssignmentNode(subject = subject.transform(), operator = operator, value = value.transform())
        is NavigationNode -> copy(subject = subject.transform())
        is IndexOpNode -> IndexOpNode(position = position, subject = subject.transform(), arguments = arguments.map { it.transform() })
        is IfNode -> copy(
            condition = condition.transform(),
            trueBlock = trueBlock?.transformBlock(functionNames, mapFunctionName),
            falseBlock = falseBlock?.transformBlock(functionNames, mapFunctionName),
        )
        is WhileNode -> copy(condition = condition.transform(), body = body?.transformBlock(functionNames, mapFunctionName))
        is DoWhileNode -> copy(condition = condition.transform(), body = body?.transformBlock(functionNames, mapFunctionName))
        is ForNode -> copy(subject = subject.transform(), body = body.transformBlock(functionNames, mapFunctionName))
        is ReturnNode -> copy(value = value?.transform())
        is ThrowNode -> copy(value = value.transform())
        is TryNode -> copy(
            mainBlock = mainBlock.transformBlock(functionNames, mapFunctionName),
            catchBlocks = catchBlocks.map { it.copy(block = it.block.transformBlock(functionNames, mapFunctionName)) },
            finallyBlock = finallyBlock?.transformBlock(functionNames, mapFunctionName),
        )
        is WhenNode -> copy(
            subject = subject?.copy(value = subject.value.transform()),
            entries = entries.map { entry ->
                WhenEntryNode(
                    position = entry.position,
                    conditions = entry.conditions.map { condition ->
                        condition.copy(expression = condition.expression.transform())
                    },
                    body = entry.body.transformBlock(functionNames, mapFunctionName),
                )
            },
        )
        is LambdaLiteralNode -> copy(
            declaredValueParameters = declaredValueParameters.map { it.transformParameter(functionNames, mapFunctionName) },
            body = body.transformBlock(functionNames, mapFunctionName),
        )
        is StringNode -> StringNode(position, nodes.map { it.transform() })
        else -> this
    }
}

private fun FunctionValueParameterNode.transformParameter(
    functionNames: Set<String>,
    mapFunctionName: (String) -> String,
): FunctionValueParameterNode {
    return copy(
        defaultValue = defaultValue?.transformKatariNode(functionNames, mapFunctionName),
    )
}

private fun BlockNode.transformBlock(
    functionNames: Set<String>,
    mapFunctionName: (String) -> String,
): BlockNode {
    return copy(statements = statements.map { it.transformKatariNode(functionNames, mapFunctionName) })
}
