package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.Interpreter
import com.sunnychung.lib.multiplatform.kotlite.SemanticAnalyzer
import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.model.*

data class KatariNarrativeAnalysis(
    val sourceScript: ScriptNode,
    val importedScript: ScriptNode,
    val semanticScript: ScriptNode,
    val semanticAnalyzer: SemanticAnalyzer,
    val nameAliases: Map<String, String> = emptyMap(),
    val scriptNamespaces: Map<String, Set<String>> = emptyMap(),
    val enumDefinitions: Map<String, KatariEnumDefinition> = emptyMap(),
    val program: KatariProgram? = null,
)

fun KatariNarrativeProgram(
    filename: String,
    code: String,
    bindings: KatariBindings = NarrativeBindings { registerBuiltinFunctions(NarrativeNoOpHost) },
    sourceProvider: KatariSourceProvider = EmptyKatariSourceProvider,
): KatariProgram = analyzeKatariNarrativeProgram(filename, code, bindings, sourceProvider).program
    ?: error("Katari narrative compilation did not produce a program")

fun analyzeKatariNarrativeProgram(
    filename: String,
    code: String,
    bindings: KatariBindings = NarrativeBindings { registerBuiltinFunctions(NarrativeNoOpHost) },
    sourceProvider: KatariSourceProvider = EmptyKatariSourceProvider,
): KatariNarrativeAnalysis {
    val analysis = analyzeKatariNarrativeScript(filename, code, bindings, sourceProvider)
    val declarations = bindings.executionEnvironment.getBuiltinFunctions(analysis.semanticAnalyzer.symbolTable)
    val program = KatariCompiler(
        inlineEnvironmentFunctions = declarations,
        importedEnumDefinitions = bindings.enumDefinitions,
        nameAliases = bindings.importAliases + analysis.nameAliases,
        scriptNamespaces = analysis.scriptNamespaces,
        runtimeSymbolTable = analysis.semanticAnalyzer.symbolTable,
    ).compile(analysis.importedScript)
    return analysis.copy(program = program)
}

fun analyzeKatariNarrativeScript(
    filename: String,
    code: String,
    bindings: KatariBindings = NarrativeBindings { registerBuiltinFunctions(NarrativeNoOpHost) },
    sourceProvider: KatariSourceProvider = EmptyKatariSourceProvider,
): KatariNarrativeAnalysis {
    val ast = KatariParser(Lexer(filename = filename, code = code, isParseSingleQuotedString = true)).narrativeScript()
    val imports = resolveKatariImports(filename, ast, sourceProvider)
    val semanticScript = imports.script.lowerNarrativeStringStatements(imports.scriptNamespaces)
    bindings.executionEnvironment.installKatariTaskSemanticTypes()
    bindings.executionEnvironment.installKatariDataSemanticTypes()
    val semanticAnalyzer = SemanticAnalyzer(semanticScript, bindings.executionEnvironment)
    semanticAnalyzer.analyze()
    return KatariNarrativeAnalysis(
        sourceScript = ast,
        importedScript = imports.script,
        semanticScript = semanticScript,
        semanticAnalyzer = semanticAnalyzer,
        nameAliases = imports.nameAliases,
        scriptNamespaces = imports.scriptNamespaces,
        enumDefinitions = bindings.enumDefinitions,
    )
}

internal fun ExecutionEnvironment.installKatariDataSemanticTypes() {
    if (findProvidedClass(XML_VALUE_TYPE_ID) == null) {
        registerClass(katariDataSemanticClass(XML_VALUE_TYPE_ID))
    }
    if (findProvidedClass(STRUCT_VALUE_TYPE_ID) == null) {
        registerClass(katariDataSemanticClass(STRUCT_VALUE_TYPE_ID))
        structValueSemanticFunctions().forEach(::registerFunction)
        registerExtensionProperty(
            ExtensionProperty(
                declaredName = "size",
                receiver = STRUCT_VALUE_TYPE_ID,
                type = "Int",
                getter = { interpreter, receiver, _ ->
                    val struct = receiver as? StructValue
                        ?: throw IllegalArgumentException("Struct getter `size` requires StructValue receiver")
                    IntValue(struct.fields.size, interpreter.symbolTable())
                }
            )
        )
    }
    if (findProvidedClass(STRUCT_ARRAY_VALUE_TYPE_ID) == null) {
        registerClass(katariDataSemanticClass(STRUCT_ARRAY_VALUE_TYPE_ID))
    }
}

private fun katariDataSemanticClass(typeId: String): ProvidedClassDefinition {
    return ProvidedClassDefinition(
        fullQualifiedName = typeId,
        typeParameters = emptyList(),
        isInstanceCreationAllowed = false,
        primaryConstructorParameters = emptyList(),
        constructInstance = { _, _, _ -> throw UnsupportedOperationException("$typeId is created by literal syntax") },
        position = SourcePosition.BUILTIN,
    )
}

private fun structValueSemanticFunctions(): List<CustomFunctionDefinition> {
    return listOf(
        structValueGetter("get", "Any", setOf(FunctionModifier.operator)) { _, struct, key ->
            struct.requireField(key)
        },
        structValueGetter("getInt", "Int") { _, struct, key ->
            struct.requireField<IntValue>(key, "Int")
        },
        structValueGetter("getBoolean", "Boolean") { _, struct, key ->
            struct.requireField<BooleanValue>(key, "Boolean")
        },
        structValueGetter("getDouble", "Double") { interpreter, struct, key ->
            when (val value = struct.requireField(key)) {
                is DoubleValue -> value
                is IntValue -> DoubleValue(value.value.toDouble(), interpreter.symbolTable())
                else -> throw IllegalArgumentException("Struct field `$key` has type `${value.type().descriptiveName}`, expected `Double`")
            }
        },
        structValueGetter("getString", "String") { _, struct, key ->
            struct.requireField<StringValue>(key, "String")
        },
        structValueGetter("getStruct", STRUCT_VALUE_TYPE_ID) { _, struct, key ->
            struct.requireField<StructValue>(key, STRUCT_VALUE_TYPE_ID)
        },
        structValueGetter("getArray", STRUCT_ARRAY_VALUE_TYPE_ID) { _, struct, key ->
            struct.requireField<StructArrayValue>(key, STRUCT_ARRAY_VALUE_TYPE_ID)
        },
    )
}

private fun structValueGetter(
    name: String,
    returnType: String,
    modifiers: Set<FunctionModifier> = emptySet(),
    getter: (interpreter: Interpreter, struct: StructValue, key: String) -> RuntimeValue,
): CustomFunctionDefinition {
    return CustomFunctionDefinition(
        position = SourcePosition.BUILTIN,
        receiverType = STRUCT_VALUE_TYPE_ID,
        functionName = name,
        returnType = returnType,
        parameterTypes = listOf(CustomFunctionParameter("key", "String")),
        modifiers = modifiers,
        executable = { interpreter, receiver, args, _ ->
            val struct = receiver as? StructValue
                ?: throw IllegalArgumentException("Struct getter `$name` requires StructValue receiver")
            val key = (args.singleOrNull() as? StringValue)?.value
                ?: throw IllegalArgumentException("Struct getter `$name` requires a String key")
            getter(interpreter, struct, key)
        },
    )
}

private fun StructValue.requireField(key: String): RuntimeValue {
    return fields[key] ?: throw NoSuchElementException("Struct has no field `$key`")
}

private inline fun <reified T : RuntimeValue> StructValue.requireField(key: String, expectedType: String): T {
    val value = requireField(key)
    return value as? T
        ?: throw IllegalArgumentException("Struct field `$key` has type `${value.type().descriptiveName}`, expected `$expectedType`")
}

private fun ExecutionEnvironment.installKatariTaskSemanticTypes() {
    if (findProvidedClass(KATARI_TASK_TYPE_ID) == null) {
        registerClass(
            ProvidedClassDefinition(
                fullQualifiedName = KATARI_TASK_TYPE_ID,
                typeParameters = emptyList(),
                isInstanceCreationAllowed = false,
                primaryConstructorParameters = emptyList(),
                constructInstance = { _, _, _ -> throw UnsupportedOperationException("KatariTask is created by async") },
                position = SourcePosition.BUILTIN,
                functions = listOf(
                    katariTaskSemanticFunction("start", "Unit"),
                    katariTaskSemanticFunction("stop", "Unit"),
                    katariTaskSemanticFunction("pause", "Unit"),
                    katariTaskSemanticFunction("resume", "Unit"),
                    katariTaskSemanticFunction("join", "Any?"),
                ),
            )
        )
    }
}

private fun katariTaskSemanticFunction(name: String, returnType: String): CustomFunctionDefinition {
    return CustomFunctionDefinition(
        position = SourcePosition.BUILTIN,
        receiverType = KATARI_TASK_TYPE_ID,
        functionName = name,
        returnType = returnType,
        parameterTypes = emptyList(),
        modifiers = emptySet(),
        executable = { _, _, _, _ -> NullValue },
    )
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

        is XmlNodeLiteralNode -> copy(
            attributes = attributes.map {
                it.copy(value = it.value.lowerNarrativeExpression(scriptNamespaces))
            },
            children = children.map { it.lowerNarrativeExpression(scriptNamespaces) },
        )

        is XmlAttributeLiteralNode -> copy(
            value = value.lowerNarrativeExpression(scriptNamespaces),
        )

        is StructLiteralNode -> copy(
            entries = entries.map { it.lowerNarrativeExpression(scriptNamespaces) as StructEntryLiteralNode },
        )

        is StructEntryLiteralNode -> copy(
            value = value.lowerNarrativeExpression(scriptNamespaces),
        )

        is StructArrayLiteralNode -> copy(
            elements = elements.map { it.lowerNarrativeExpression(scriptNamespaces) },
        )

        is PropertyDeclarationNode -> copy(
            initialValue = initialValue?.lowerNarrativeExpression(scriptNamespaces),
        )

        is com.sunnychung.lib.multiplatform.kotlite.model.NarrativeAsyncNode -> copy(
            body = body.lowerNarrativeStringStatements(scriptNamespaces),
        )

        is com.sunnychung.lib.multiplatform.kotlite.model.NarrativeRaceNode -> copy(
            entries = entries.map { entry ->
                entry.copy(
                    action = entry.action.lowerNarrativeExpression(scriptNamespaces),
                    result = entry.result.lowerNarrativeExpression(scriptNamespaces),
                )
            },
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
