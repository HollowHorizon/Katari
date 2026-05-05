package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter
import com.sunnychung.lib.multiplatform.kotlite.SemanticAnalyzer
import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.model.ASTNode
import com.sunnychung.lib.multiplatform.kotlite.model.AssignmentNode
import com.sunnychung.lib.multiplatform.kotlite.model.BlockNode
import com.sunnychung.lib.multiplatform.kotlite.model.ClassMemberReferenceNode
import com.sunnychung.lib.multiplatform.kotlite.model.ForNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallArgumentNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.IfNode
import com.sunnychung.lib.multiplatform.kotlite.model.IndexOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.LambdaLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.NavigationNode
import com.sunnychung.lib.multiplatform.kotlite.model.PropertyDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.ReturnNode
import com.sunnychung.lib.multiplatform.kotlite.model.ScriptNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringNode
import com.sunnychung.lib.multiplatform.kotlite.model.VariableReferenceNode
import com.sunnychung.lib.multiplatform.kotlite.model.WhileNode

fun KatariNarrativeProgram(
    filename: String,
    code: String,
    bindings: KatariBindings = NarrativeBindings { registerBuiltinFunctions(NarrativeNoOpHost) },
    sourceProvider: KatariSourceProvider = EmptyKatariSourceProvider,
): KatariProgram {
    val ast = KatariParser(Lexer(filename = filename, code = code, isParseSingleQuotedString = true)).narrativeScript()
    val imports = resolveKatariImports(filename, ast, sourceProvider)
    val semanticScript = imports.script.lowerNarrativeStringStatements(imports.scriptNamespaces)
    SemanticAnalyzer(semanticScript, bindings.executionEnvironment).analyze()
    val interpreter = KotliteInterpreter(
        filename = "<NarrativeInline>",
        code = "",
        executionEnvironment = bindings.executionEnvironment,
    )
    val declarations = bindings.executionEnvironment.getBuiltinFunctions(interpreter.symbolTable())
    return KatariCompiler(
        inlineEnvironmentFunctions = declarations,
        importedEnumDefinitions = bindings.enumDefinitions,
        nameAliases = bindings.importAliases + imports.nameAliases,
        scriptNamespaces = imports.scriptNamespaces,
    ).compile(imports.script)
}

private fun ScriptNode.lowerNarrativeStringStatements(scriptNamespaces: Map<String, Set<String>>): ScriptNode {
    return copy(nodes = nodes.map { it.lowerNarrativeStatement(scriptNamespaces) })
}

private fun BlockNode.lowerNarrativeStringStatements(scriptNamespaces: Map<String, Set<String>>): BlockNode {
    return copy(statements = statements.map { it.lowerNarrativeStatement(scriptNamespaces) })
}

private fun ASTNode.lowerNarrativeStatement(scriptNamespaces: Map<String, Set<String>>): ASTNode {
    return when (this) {
        is StringLiteralNode -> narrateCall(this)
        is StringNode -> narrateCall(this)
        is ScriptNode -> lowerNarrativeStringStatements(scriptNamespaces)
        is BlockNode -> lowerNarrativeStringStatements(scriptNamespaces)
        else -> lowerNarrativeExpression(scriptNamespaces)
    }
}

private fun ASTNode.lowerNarrativeExpression(scriptNamespaces: Map<String, Set<String>>): ASTNode {
    return when (this) {
        is AssignmentNode -> lowerNarrativeAssignment(scriptNamespaces)
        is ForNode -> copy(
            subject = subject.lowerNarrativeExpression(scriptNamespaces),
            body = body.lowerNarrativeStringStatements(scriptNamespaces),
        )
        is FunctionCallNode -> copy(
            function = function.lowerNamespacedFunctionReference(scriptNamespaces),
            arguments = arguments.map { argument ->
                argument.copy(value = argument.value.lowerNarrativeExpression(scriptNamespaces))
            },
        )
        is FunctionDeclarationNode -> copy(
            body = body?.lowerNarrativeStringStatements(scriptNamespaces),
        )
        is IfNode -> copy(
            condition = condition.lowerNarrativeExpression(scriptNamespaces),
            trueBlock = trueBlock?.lowerNarrativeStringStatements(scriptNamespaces),
            falseBlock = falseBlock?.lowerNarrativeStringStatements(scriptNamespaces),
        )
        is LambdaLiteralNode -> copy(
            body = body.lowerNarrativeStringStatements(scriptNamespaces),
        )
        is PropertyDeclarationNode -> copy(
            initialValue = initialValue?.lowerNarrativeExpression(scriptNamespaces),
        )
        is ReturnNode -> copy(
            value = value?.lowerNarrativeExpression(scriptNamespaces),
        )
        is WhileNode -> copy(
            condition = condition.lowerNarrativeExpression(scriptNamespaces),
            body = body?.lowerNarrativeStringStatements(scriptNamespaces),
        )
        else -> this
    }
}

private fun AssignmentNode.lowerNarrativeAssignment(scriptNamespaces: Map<String, Set<String>>): ASTNode {
    val indexTarget = subject as? IndexOpNode
    if (operator == "=" && indexTarget != null) {
        return FunctionCallNode(
            function = NavigationNode(
                position = position,
                subject = indexTarget.subject.lowerNarrativeExpression(scriptNamespaces),
                operator = ".",
                member = ClassMemberReferenceNode(position, "set"),
            ),
            arguments = indexTarget.arguments.mapIndexed { index, argument ->
                FunctionCallArgumentNode(
                    position = argument.position,
                    index = index,
                    value = argument.lowerNarrativeExpression(scriptNamespaces),
                )
            } + FunctionCallArgumentNode(
                position = value.position,
                index = indexTarget.arguments.size,
                value = value.lowerNarrativeExpression(scriptNamespaces),
            ),
            declaredTypeArguments = emptyList(),
            position = position,
        )
    }
    return copy(
        subject = subject.lowerNarrativeExpression(scriptNamespaces),
        value = value.lowerNarrativeExpression(scriptNamespaces),
    )
}

private fun ASTNode.lowerNamespacedFunctionReference(scriptNamespaces: Map<String, Set<String>>): ASTNode {
    val navigation = this as? NavigationNode ?: return this
    val namespace = navigation.subject as? VariableReferenceNode ?: return this
    if (navigation.member.name !in scriptNamespaces[namespace.variableName].orEmpty()) return this
    return VariableReferenceNode(navigation.position, "${namespace.variableName}.${navigation.member.name}")
}

private fun narrateCall(text: ASTNode): FunctionCallNode {
    return FunctionCallNode(
        function = VariableReferenceNode(text.position, "narrate"),
        arguments = listOf(
            FunctionCallArgumentNode(
                position = text.position,
                index = 0,
                value = text,
            )
        ),
        declaredTypeArguments = emptyList(),
        position = text.position,
    )
}
