package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.model.ASTNode
import com.sunnychung.lib.multiplatform.kotlite.model.AssignmentNode
import com.sunnychung.lib.multiplatform.kotlite.model.BinaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.BreakNode
import com.sunnychung.lib.multiplatform.kotlite.model.BlockNode
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanNode
import com.sunnychung.lib.multiplatform.kotlite.model.ContinueNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.IfNode
import com.sunnychung.lib.multiplatform.kotlite.model.IntegerNode
import com.sunnychung.lib.multiplatform.kotlite.model.InfixFunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCheckpointNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseEntryNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeJumpNode
import com.sunnychung.lib.multiplatform.kotlite.model.NavigationNode
import com.sunnychung.lib.multiplatform.kotlite.model.NullNode
import com.sunnychung.lib.multiplatform.kotlite.model.PropertyDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.ReturnNode
import com.sunnychung.lib.multiplatform.kotlite.model.ScriptNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringFieldIdentifierNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringNode
import com.sunnychung.lib.multiplatform.kotlite.model.UnaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.VariableReferenceNode
import com.sunnychung.lib.multiplatform.kotlite.model.WhileNode

class NarrativeCompiler {

    private var temporarySlotCounter: Int = 0
    private val loopContexts = ArrayDeque<LoopContext>()
    private val checkpoints = mutableMapOf<String, Int>()
    private val unresolvedJumps = mutableListOf<UnresolvedJump>()
    private val userFunctions = mutableMapOf<String, FunctionDeclarationNode>()
    private val userFunctionCompilationStack = ArrayDeque<String>()
    private val variableAliasScopes = ArrayDeque<Map<String, String>>()

    fun compile(script: ScriptNode): NarrativeProgram {
        temporarySlotCounter = 0
        loopContexts.clear()
        checkpoints.clear()
        unresolvedJumps.clear()
        userFunctions.clear()
        userFunctionCompilationStack.clear()
        variableAliasScopes.clear()

        collectTopLevelUserFunctions(script)

        val instructions = mutableListOf<NarrativeInstruction>()
        compileStatements(script.nodes.filterNot { it is FunctionDeclarationNode }, instructions)
        resolveCheckpointJumps(instructions)
        instructions += EndInstruction(position = script.position)
        return NarrativeProgram(instructions = instructions)
    }

    private fun compileStatements(statements: List<ASTNode>, instructions: MutableList<NarrativeInstruction>) {
        statements.forEach { compileStatement(it, instructions) }
    }

    private fun compileStatement(statement: ASTNode, instructions: MutableList<NarrativeInstruction>) {
        when (statement) {
            is ScriptNode -> compileStatements(statement.nodes, instructions)
            is BlockNode -> compileStatements(statement.statements, instructions)
            is IfNode -> compileIf(statement, instructions)
            is WhileNode -> compileWhile(statement, instructions)
            is PropertyDeclarationNode -> compilePropertyDeclaration(statement, instructions)
            is AssignmentNode -> compileAssignment(statement, instructions)
            is BreakNode -> compileBreak(statement, instructions)
            is ContinueNode -> compileContinue(statement, instructions)
            is NarrativeCheckpointNode -> compileCheckpoint(statement, instructions)
            is NarrativeJumpNode -> compileJump(statement, instructions)
            is NarrativeChooseNode -> compileChoose(statement, instructions)
            is FunctionDeclarationNode -> throw UnsupportedOperationException(
                "${statement.position} Local narrative function declarations are not supported. Declare functions at top-level only."
            )
            is UnaryOpNode -> compileUnaryStatement(statement, instructions)
            is StringLiteralNode -> instructions += CallFunctionInstruction(
                functionId = "narrate",
                arguments = listOf(LiteralExpression(NarrativeValue.Text(statement.content))),
                position = statement.position,
            )
            is StringNode -> instructions += CallFunctionInstruction(
                functionId = "narrate",
                arguments = listOf(compileStringExpression(statement, instructions)),
                position = statement.position,
            )
            is FunctionCallNode -> {
                if (!compileUserFunctionCall(statement, instructions)) {
                    instructions += compileCall(statement, instructions)
                }
            }
            else -> throw UnsupportedOperationException("${statement.position} Narrative compiler does not support `${statement::class.simpleName}` yet")
        }
    }

    private fun collectTopLevelUserFunctions(script: ScriptNode) {
        script.nodes.filterIsInstance<FunctionDeclarationNode>().forEach { function ->
            validateUserFunctionDeclaration(function)
            require(function.name !in userFunctions) {
                "${function.position} Narrative function `${function.name}` is declared more than once"
            }
            userFunctions[function.name] = function
        }
    }

    private fun validateUserFunctionDeclaration(function: FunctionDeclarationNode) {
        require(function.receiver == null) {
            "${function.position} Narrative user function `${function.name}` cannot have a receiver"
        }
        require(function.typeParameters.isEmpty() && function.extraTypeParameters.isEmpty()) {
            "${function.position} Narrative user function `${function.name}` cannot declare type parameters"
        }
        require(function.body != null) {
            "${function.position} Narrative user function `${function.name}` must have a body"
        }
        validateUserFunctionReturns(function)
    }

    private fun validateUserFunctionReturns(function: FunctionDeclarationNode) {
        val body = function.body ?: return
        if (body.statements.isEmpty()) {
            return
        }
        val returnPositions = mutableListOf<ReturnNode>()
        collectReturnNodes(body, returnPositions)
        if (returnPositions.isEmpty()) {
            return
        }
        val lastStatement = body.statements.last()
        require(returnPositions.size == 1 && lastStatement is ReturnNode) {
            "${function.position} Narrative user function `${function.name}` can only use a single trailing return statement"
        }
    }

    private fun collectReturnNodes(node: ASTNode, output: MutableList<ReturnNode>) {
        when (node) {
            is ReturnNode -> output += node
            is BlockNode -> node.statements.forEach { collectReturnNodes(it, output) }
            is IfNode -> {
                node.trueBlock?.let { collectReturnNodes(it, output) }
                node.falseBlock?.let { collectReturnNodes(it, output) }
            }
            is WhileNode -> node.body?.let { collectReturnNodes(it, output) }
            else -> Unit
        }
    }

    private fun compileCheckpoint(
        node: NarrativeCheckpointNode,
        instructions: MutableList<NarrativeInstruction>,
    ) {
        val label = node.label
        require(label !in checkpoints) { "Checkpoint `$label` is declared more than once" }
        checkpoints[label] = instructions.size
    }

    private fun compileJump(
        node: NarrativeJumpNode,
        instructions: MutableList<NarrativeInstruction>,
    ) {
        val label = node.label
        unresolvedJumps += UnresolvedJump(label = label, instructionIndex = instructions.size)
        instructions += JumpInstruction(target = -1, position = node.position)
    }

    private fun compileChoose(
        node: NarrativeChooseNode,
        instructions: MutableList<NarrativeInstruction>,
    ) {
        require(node.entries.isNotEmpty()) { "Narrative choose block cannot be empty" }
        val choiceVariable = "__narrative_choose_${nextTemporarySlot()}"
        val compiledEntries = node.entries.mapIndexed { index, entry ->
            val indexText = index.toString()
            val optionTextExpression = compileExpression(entry.text, instructions)
            CompiledChooseEntry(
                entry = entry,
                argumentExpression = compileChooseEntryArgument(entry, optionTextExpression, instructions),
                selectedIdExpression = LiteralExpression(NarrativeValue.Text(indexText)),
            )
        }
        instructions += CallFunctionInstruction(
            functionId = "chooseIndexed",
            arguments = compiledEntries.map { it.argumentExpression },
            resultTarget = NarrativeResultTarget.Variable(choiceVariable),
            position = node.position,
        )

        val endJumpIndices = mutableListOf<Int>()
        compiledEntries.forEachIndexed { index, compiledEntry ->
            val entry = compiledEntry.entry
            val condition = BinaryExpression(
                left = VariableExpression(choiceVariable),
                operator = NarrativeBinaryOperator.Equals,
                right = compiledEntry.selectedIdExpression,
            )
            val conditionalIndex = instructions.size
            instructions += ConditionalJumpInstruction(
                condition = condition,
                falseTarget = -1,
                position = entry.position,
            )
            compileStatement(entry.action, instructions)
            if (index < node.entries.lastIndex) {
                endJumpIndices += instructions.size
                instructions += JumpInstruction(target = -1, position = entry.position)
            }
            val falseTarget = instructions.size
            instructions[conditionalIndex] = ConditionalJumpInstruction(
                condition = condition,
                falseTarget = falseTarget,
                position = entry.position,
            )
        }
        val endTarget = instructions.size
        instructions += RemoveVariablesInstruction(
            names = listOf(choiceVariable),
            position = node.position,
        )
        endJumpIndices.forEach { jumpIndex ->
            instructions[jumpIndex] = JumpInstruction(
                target = endTarget,
                position = instructions[jumpIndex].position,
            )
        }
    }

    private fun compileChooseEntryArgument(
        entry: NarrativeChooseEntryNode,
        textExpression: NarrativeExpression,
        instructions: MutableList<NarrativeInstruction>,
    ): NarrativeExpression {
        if (entry.visibleCondition == null && entry.disableCondition == null) {
            return textExpression
        }
        val slot = nextTemporarySlot()
        val visibleExpression = entry.visibleCondition?.let { compileExpression(it, instructions) }
            ?: LiteralExpression(NarrativeValue.Bool(true))
        val enabledExpression = entry.disableCondition?.let {
            UnaryExpression(
                operator = NarrativeUnaryOperator.Not,
                operand = compileExpression(it, instructions),
            )
        } ?: LiteralExpression(NarrativeValue.Bool(true))
        val disabledTextExpression = entry.disabledText?.let { compileExpression(it, instructions) }
            ?: LiteralExpression(NarrativeValue.Null)
        instructions += CallFunctionInstruction(
            functionId = "choiceOption",
            arguments = listOf(
                textExpression,
                visibleExpression,
                enabledExpression,
                disabledTextExpression,
            ),
            resultTarget = NarrativeResultTarget.Slot(slot),
            position = entry.position,
        )
        return SlotExpression(slot)
    }

    private fun resolveCheckpointJumps(instructions: MutableList<NarrativeInstruction>) {
        unresolvedJumps.forEach { jump ->
            val target = checkpoints[jump.label]
                ?: throw UnsupportedOperationException("Narrative jump target `${jump.label}` is not defined")
            instructions[jump.instructionIndex] = JumpInstruction(
                target = target,
                position = instructions[jump.instructionIndex].position,
            )
        }
    }

    private fun compilePropertyDeclaration(node: PropertyDeclarationNode, instructions: MutableList<NarrativeInstruction>) {
        val initialValue = node.initialValue
            ?: throw UnsupportedOperationException("Narrative property `${node.name}` requires an initializer")
        if (initialValue is FunctionCallNode) {
            val declaration = (initialValue.function as? VariableReferenceNode)
                ?.variableName
                ?.let { userFunctions[it] }
            if (declaration != null) {
                compileUserFunctionInvocation(
                    declaration = declaration,
                    callNode = initialValue,
                    instructions = instructions,
                    resultTarget = NarrativeResultTarget.Variable(resolveVariableName(node.name)),
                )
            } else {
                instructions += compileCall(
                    node = initialValue,
                    instructions = instructions,
                    resultTarget = NarrativeResultTarget.Variable(resolveVariableName(node.name)),
                )
            }
            return
        }
        instructions += SetVariableInstruction(
            name = resolveVariableName(node.name),
            expression = compileExpression(initialValue, instructions),
            position = node.position,
        )
    }

    private fun compileAssignment(node: AssignmentNode, instructions: MutableList<NarrativeInstruction>) {
        val target = node.subject as? VariableReferenceNode
            ?: throw UnsupportedOperationException("Narrative assignment target must be a variable reference")
        val targetName = resolveVariableName(target.variableName)
        when (node.operator) {
            "=" -> {
                if (node.value is FunctionCallNode) {
                    val declaration = (node.value.function as? VariableReferenceNode)
                        ?.variableName
                        ?.let { userFunctions[it] }
                    if (declaration != null) {
                        compileUserFunctionInvocation(
                            declaration = declaration,
                            callNode = node.value,
                            instructions = instructions,
                            resultTarget = NarrativeResultTarget.Variable(targetName),
                        )
                    } else {
                        instructions += compileCall(
                            node = node.value,
                            instructions = instructions,
                            resultTarget = NarrativeResultTarget.Variable(targetName),
                        )
                    }
                    return
                }
                instructions += SetVariableInstruction(
                    name = targetName,
                    expression = compileExpression(node.value, instructions),
                    position = node.position,
                )
            }
            "+=", "-=" -> {
                val operator = if (node.operator == "+=") NarrativeBinaryOperator.Add else NarrativeBinaryOperator.Subtract
                instructions += SetVariableInstruction(
                    name = targetName,
                    expression = BinaryExpression(
                        left = VariableExpression(targetName),
                        operator = operator,
                        right = compileExpression(node.value, instructions),
                    ),
                    position = node.position,
                )
            }
            else -> throw UnsupportedOperationException("e${node.position}: Narrative assignment `${node.operator}` is not supported yet")
        }
    }

    private fun compileIfExpression(node: IfNode, instructions: MutableList<NarrativeInstruction>): NarrativeExpression {
        val slot = nextTemporarySlot()
        val target = NarrativeResultTarget.Slot(slot)
        val condition = compileExpression(node.condition, instructions)
        val conditionalIndex = instructions.size
        instructions += ConditionalJumpInstruction(
            condition = condition,
            falseTarget = -1,
            position = node.position,
        )
        compileBranchExpression(node.trueBlock, target, instructions)
        val jumpIndex = instructions.size
        instructions += JumpInstruction(target = -1, position = node.position)
        val falseTarget = instructions.size
        compileBranchExpression(node.falseBlock, target, instructions)
        val afterFalse = instructions.size
        instructions[conditionalIndex] = ConditionalJumpInstruction(
            condition = condition,
            falseTarget = falseTarget,
            position = node.position,
        )
        instructions[jumpIndex] = JumpInstruction(target = afterFalse, position = node.position)
        return SlotExpression(slot)
    }

    private fun compileBranchExpression(
        block: BlockNode?,
        target: NarrativeResultTarget,
        instructions: MutableList<NarrativeInstruction>,
    ) {
        val statements = block?.statements ?: listOf(NullNode)
        if (statements.isEmpty()) {
            instructions += SetResultInstruction(target, LiteralExpression(NarrativeValue.Null), block?.position)
            return
        }
        statements.dropLast(1).forEach { compileStatement(it, instructions) }
        compileExpressionIntoTarget(statements.last(), target, instructions)
    }

    private fun compileExpressionIntoTarget(
        expression: ASTNode,
        target: NarrativeResultTarget,
        instructions: MutableList<NarrativeInstruction>,
    ) {
        if (expression is FunctionCallNode) {
            val declaration = (expression.function as? VariableReferenceNode)
                ?.variableName
                ?.let { userFunctions[it] }
            if (declaration != null) {
                compileUserFunctionInvocation(
                    declaration = declaration,
                    callNode = expression,
                    instructions = instructions,
                    resultTarget = target,
                )
            } else {
                instructions += compileCall(
                    node = expression,
                    instructions = instructions,
                    resultTarget = target,
                )
            }
            return
        }
        instructions += SetResultInstruction(
            target = target,
            expression = compileExpression(expression, instructions),
            position = expression.position,
        )
    }

    private fun compileWhile(node: WhileNode, instructions: MutableList<NarrativeInstruction>) {
        val loopStart = instructions.size
        val loopContext = LoopContext(continueTarget = loopStart)
        loopContexts.addLast(loopContext)
        val condition = compileExpression(node.condition, instructions)
        val conditionalIndex = instructions.size
        instructions += ConditionalJumpInstruction(
            condition = condition,
            falseTarget = -1,
            position = node.position,
        )
        node.body?.let { compileStatement(it, instructions) }
        instructions += JumpInstruction(target = loopStart, position = node.position)
        val loopExit = instructions.size
        instructions[conditionalIndex] = ConditionalJumpInstruction(
            condition = condition,
            falseTarget = loopExit,
            position = node.position,
        )
        loopContext.breakJumpIndices.forEach { breakIndex ->
            instructions[breakIndex] = JumpInstruction(target = loopExit, position = instructions[breakIndex].position)
        }
        loopContexts.removeLast()
    }

    private fun compileBreak(node: BreakNode, instructions: MutableList<NarrativeInstruction>) {
        val loopContext = loopContexts.lastOrNull()
            ?: throw UnsupportedOperationException("Narrative `break` can only be used inside a loop")
        loopContext.breakJumpIndices += instructions.size
        instructions += JumpInstruction(target = -1, position = node.position)
    }

    private fun compileContinue(node: ContinueNode, instructions: MutableList<NarrativeInstruction>) {
        val loopContext = loopContexts.lastOrNull()
            ?: throw UnsupportedOperationException("Narrative `continue` can only be used inside a loop")
        instructions += JumpInstruction(target = loopContext.continueTarget, position = node.position)
    }

    private fun compileIf(node: IfNode, instructions: MutableList<NarrativeInstruction>) {
        val condition = compileExpression(node.condition, instructions)
        val conditionalIndex = instructions.size
        instructions += ConditionalJumpInstruction(
            condition = condition,
            falseTarget = -1,
            position = node.position,
        )
        node.trueBlock?.let { compileStatement(it, instructions) }
        if (node.falseBlock != null) {
            val jumpIndex = instructions.size
            instructions += JumpInstruction(target = -1, position = node.position)
            val falseTarget = instructions.size
            compileStatement(node.falseBlock, instructions)
            val afterFalse = instructions.size
            instructions[conditionalIndex] = ConditionalJumpInstruction(
                condition = condition,
                falseTarget = falseTarget,
                position = node.position,
            )
            instructions[jumpIndex] = JumpInstruction(target = afterFalse, position = node.position)
        } else {
            instructions[conditionalIndex] = ConditionalJumpInstruction(
                condition = condition,
                falseTarget = instructions.size,
                position = node.position,
            )
        }
    }

    private fun compileUnaryStatement(node: UnaryOpNode, instructions: MutableList<NarrativeInstruction>) {
        when (node.operator) {
            "pre++", "post++", "pre--", "post--" -> {
                compileIncDecExpression(node, instructions)
            }
            else -> throw UnsupportedOperationException(
                "Narrative unary statement `${node.operator}` is not supported"
            )
        }
    }

    private fun compileCall(
        node: FunctionCallNode,
        instructions: MutableList<NarrativeInstruction>,
        resultTarget: NarrativeResultTarget? = null,
    ): NarrativeInstruction {
        val arguments = node.arguments.map { compileExpression(it.value, instructions) }
        return when (val function = node.function) {
            is VariableReferenceNode -> CallFunctionInstruction(
                functionId = function.variableName,
                arguments = arguments,
                resultTarget = resultTarget,
                position = node.position,
            )
            is NavigationNode -> {
                CallFunctionInstruction(
                    functionId = function.member.name,
                    arguments = listOf(compileExpression(function.subject, instructions)) + arguments,
                    resultTarget = resultTarget,
                    position = node.position,
                )
            }
            else -> throw UnsupportedOperationException("Narrative call `${function::class.simpleName}` is not supported")
        }
    }

    private fun compileUserFunctionInvocation(
        declaration: FunctionDeclarationNode,
        callNode: FunctionCallNode,
        instructions: MutableList<NarrativeInstruction>,
        resultTarget: NarrativeResultTarget?,
    ) {
        val functionName = declaration.name
        require(functionName !in userFunctionCompilationStack) {
            "${callNode.position} Recursive narrative user function calls are not supported (`${functionName}`)"
        }
        require(callNode.arguments.size == declaration.valueParameters.size) {
            "${callNode.position} Narrative user function `${functionName}` expects ${declaration.valueParameters.size} arguments but got ${callNode.arguments.size}"
        }

        val body = declaration.body
            ?: throw UnsupportedOperationException("${declaration.position} Narrative user function `${functionName}` must have a body")

        val parameterNames = declaration.valueParameters.map { it.name }
        val argumentExpressions = callNode.arguments.map { compileExpression(it.value, instructions) }
        val localNames = collectDeclaredVariables(body.statements)
        val aliasMap = mutableMapOf<String, String>()

        parameterNames.forEachIndexed { index, parameterName ->
            aliasMap[parameterName] = "__narrative_fn_${functionName}_${nextTemporarySlot()}_arg_${index}"
        }
        localNames.forEach { localName ->
            aliasMap.putIfAbsent(localName, "__narrative_fn_${functionName}_${nextTemporarySlot()}_local_${localName}")
        }

        parameterNames.forEachIndexed { index, parameterName ->
            val alias = aliasMap.getValue(parameterName)
            instructions += SetVariableInstruction(
                name = alias,
                expression = argumentExpressions[index],
                position = callNode.position,
            )
        }

        variableAliasScopes.addLast(aliasMap)
        userFunctionCompilationStack.addLast(functionName)
        try {
            if (resultTarget == null) {
                compileUserFunctionBodyAsStatements(body, instructions, functionName)
            } else {
                compileUserFunctionBodyAsExpression(body, instructions, resultTarget, functionName)
            }
        } finally {
            userFunctionCompilationStack.removeLast()
            variableAliasScopes.removeLast()
            instructions += RemoveVariablesInstruction(
                names = aliasMap.values.toList(),
                position = callNode.position,
            )
        }
    }

    private fun compileUserFunctionBodyAsStatements(
        body: BlockNode,
        instructions: MutableList<NarrativeInstruction>,
        functionName: String,
    ) {
        val statements = body.statements
        if (statements.isEmpty()) return
        statements.forEachIndexed { index, statement ->
            if (statement is ReturnNode) {
                require(index == statements.lastIndex) {
                    "${statement.position} Narrative user function `${functionName}` only supports trailing return"
                }
                return
            }
            compileStatement(statement, instructions)
        }
    }

    private fun compileUserFunctionBodyAsExpression(
        body: BlockNode,
        instructions: MutableList<NarrativeInstruction>,
        resultTarget: NarrativeResultTarget,
        functionName: String,
    ) {
        val statements = body.statements
        require(statements.isNotEmpty()) {
            "${body.position} Narrative user function `${functionName}` must have a non-empty body to return a value"
        }

        statements.dropLast(1).forEach { statement ->
            require(statement !is ReturnNode) {
                "${statement.position} Narrative user function `${functionName}` only supports trailing return"
            }
            compileStatement(statement, instructions)
        }

        val last = statements.last()
        when (last) {
            is ReturnNode -> {
                val returnValue = last.value ?: NullNode
                compileExpressionIntoTarget(returnValue, resultTarget, instructions)
            }
            else -> compileExpressionIntoTarget(last, resultTarget, instructions)
        }
    }

    private fun collectDeclaredVariables(statements: List<ASTNode>): Set<String> {
        val names = linkedSetOf<String>()
        statements.forEach { statement ->
            when (statement) {
                is PropertyDeclarationNode -> names += statement.name
                is BlockNode -> names += collectDeclaredVariables(statement.statements)
                is IfNode -> {
                    statement.trueBlock?.let { names += collectDeclaredVariables(it.statements) }
                    statement.falseBlock?.let { names += collectDeclaredVariables(it.statements) }
                }
                is WhileNode -> statement.body?.let { names += collectDeclaredVariables(it.statements) }
                else -> Unit
            }
        }
        return names
    }

    private fun resolveVariableName(name: String): String {
        variableAliasScopes.toList().asReversed().forEach { scope ->
            val alias = scope[name]
            if (alias != null) {
                return alias
            }
        }
        return name
    }

    private fun compileUserFunctionCall(
        node: FunctionCallNode,
        instructions: MutableList<NarrativeInstruction>,
    ): Boolean {
        val functionName = (node.function as? VariableReferenceNode)?.variableName ?: return false
        val declaration = userFunctions[functionName] ?: return false
        compileUserFunctionInvocation(
            declaration = declaration,
            callNode = node,
            instructions = instructions,
            resultTarget = null,
        )
        return true
    }

    private fun compileExpression(expression: ASTNode, instructions: MutableList<NarrativeInstruction>): NarrativeExpression {
        return when (expression) {
            is BooleanNode -> LiteralExpression(NarrativeValue.Bool(expression.value))
            is IntegerNode -> LiteralExpression(NarrativeValue.Int32(expression.value))
            NullNode -> LiteralExpression(NarrativeValue.Null)
            is StringLiteralNode -> LiteralExpression(NarrativeValue.Text(expression.content))
            is StringNode -> compileStringExpression(expression, instructions)
            is StringFieldIdentifierNode -> VariableExpression(resolveVariableName(expression.variableName))
            is VariableReferenceNode -> VariableExpression(resolveVariableName(expression.variableName))
            is IfNode -> compileIfExpression(expression, instructions)
            is FunctionCallNode -> {
                val functionName = (expression.function as? VariableReferenceNode)?.variableName
                if (functionName != null) {
                    val declaration = userFunctions[functionName]
                    if (declaration != null) {
                        val slot = nextTemporarySlot()
                        compileUserFunctionInvocation(
                            declaration = declaration,
                            callNode = expression,
                            instructions = instructions,
                            resultTarget = NarrativeResultTarget.Slot(slot),
                        )
                        return SlotExpression(slot)
                    }
                }
                val slot = nextTemporarySlot()
                instructions += compileCall(
                    node = expression,
                    instructions = instructions,
                    resultTarget = NarrativeResultTarget.Slot(slot),
                )
                SlotExpression(slot)
            }
            is InfixFunctionCallNode -> {
                when (expression.functionName) {
                    "is", "!is", "in", "!in" -> throw UnsupportedOperationException(
                        "Infix operator `${expression.functionName}` is not supported in narrative expressions"
                    )
                    else -> {
                        val slot = nextTemporarySlot()
                        instructions += CallFunctionInstruction(
                            functionId = expression.functionName,
                            arguments = listOf(
                                compileExpression(expression.node1, instructions),
                                compileExpression(expression.node2, instructions),
                            ),
                            resultTarget = NarrativeResultTarget.Slot(slot),
                            position = expression.position,
                        )
                        SlotExpression(slot)
                    }
                }
            }
            is UnaryOpNode -> {
                when (expression.operator) {
                    "pre++", "post++", "pre--", "post--" -> compileIncDecExpression(expression, instructions)
                    else -> {
                        val operator = when (expression.operator) {
                            "+" -> NarrativeUnaryOperator.Plus
                            "-" -> NarrativeUnaryOperator.Minus
                            "!" -> NarrativeUnaryOperator.Not
                            else -> throw UnsupportedOperationException(
                                "Unary operator `${expression.operator}` is not supported in narrative expressions"
                            )
                        }
                        val operand = expression.node
                            ?: throw UnsupportedOperationException("Unary operator `${expression.operator}` requires an operand")
                        UnaryExpression(
                            operator = operator,
                            operand = compileExpression(operand, instructions),
                        )
                    }
                }
            }
            is BinaryOpNode -> {
                val operator = when (expression.operator) {
                    "+" -> NarrativeBinaryOperator.Add
                    "-" -> NarrativeBinaryOperator.Subtract
                    "<" -> NarrativeBinaryOperator.LessThan
                    "<=" -> NarrativeBinaryOperator.LessThanOrEquals
                    ">" -> NarrativeBinaryOperator.GreaterThan
                    ">=" -> NarrativeBinaryOperator.GreaterThanOrEquals
                    "==" -> NarrativeBinaryOperator.Equals
                    "!=" -> NarrativeBinaryOperator.NotEquals
                    "&&" -> NarrativeBinaryOperator.And
                    "||" -> NarrativeBinaryOperator.Or
                    else -> throw UnsupportedOperationException("Binary operator `${expression.operator}` is not supported in narrative expressions")
                }
                BinaryExpression(
                    left = compileExpression(expression.node1, instructions),
                    operator = operator,
                    right = compileExpression(expression.node2, instructions),
                )
            }
            else -> throw UnsupportedOperationException("${expression.position} Narrative expression `${expression::class.simpleName}` is not supported")
        }
    }

    private fun compileStringExpression(
        node: StringNode,
        instructions: MutableList<NarrativeInstruction>,
    ): NarrativeExpression {
        if (node.nodes.size == 1 && node.nodes.first() is StringLiteralNode) {
            return compileExpression(node.nodes.first(), instructions)
        }
        return node.nodes
            .map { part -> compileExpression(part, instructions) }
            .reduce { acc, part ->
                BinaryExpression(
                    left = acc,
                    operator = NarrativeBinaryOperator.Add,
                    right = part,
                )
            }
    }

    private fun compileIncDecExpression(
        node: UnaryOpNode,
        instructions: MutableList<NarrativeInstruction>,
    ): NarrativeExpression {
        val operand = node.node
            ?: throw UnsupportedOperationException("Unary operator `${node.operator}` requires an operand")
        val variableName = (operand as? VariableReferenceNode)?.variableName?.let { resolveVariableName(it) }
            ?: throw UnsupportedOperationException(
                "Unary operator `${node.operator}` requires a variable reference operand"
            )
        val delta = when (node.operator) {
            "pre++", "post++" -> 1
            "pre--", "post--" -> -1
            else -> throw UnsupportedOperationException("Unary operator `${node.operator}` is not supported")
        }
        val updateExpression = BinaryExpression(
            left = VariableExpression(variableName),
            operator = if (delta > 0) NarrativeBinaryOperator.Add else NarrativeBinaryOperator.Subtract,
            right = LiteralExpression(NarrativeValue.Int32(kotlin.math.abs(delta))),
        )
        return when (node.operator) {
            "pre++", "pre--" -> {
                instructions += SetVariableInstruction(
                    name = variableName,
                    expression = updateExpression,
                    position = node.position,
                )
                VariableExpression(variableName)
            }
            "post++", "post--" -> {
                val slot = nextTemporarySlot()
                instructions += SetResultInstruction(
                    target = NarrativeResultTarget.Slot(slot),
                    expression = BinaryExpression(
                        left = VariableExpression(variableName),
                        operator = NarrativeBinaryOperator.Add,
                        right = LiteralExpression(NarrativeValue.Int32(0)),
                    ),
                    position = node.position,
                )
                instructions += SetVariableInstruction(
                    name = variableName,
                    expression = updateExpression,
                    position = node.position,
                )
                SlotExpression(slot)
            }
            else -> throw UnsupportedOperationException("Unary operator `${node.operator}` is not supported")
        }
    }

    private fun nextTemporarySlot(): Int {
        val slot = temporarySlotCounter
        temporarySlotCounter += 1
        return slot
    }
}

private data class LoopContext(
    val continueTarget: Int,
    val breakJumpIndices: MutableList<Int> = mutableListOf(),
)

private data class UnresolvedJump(
    val label: String,
    val instructionIndex: Int,
)

private data class CompiledChooseEntry(
    val entry: NarrativeChooseEntryNode,
    val argumentExpression: NarrativeExpression,
    val selectedIdExpression: NarrativeExpression,
)
