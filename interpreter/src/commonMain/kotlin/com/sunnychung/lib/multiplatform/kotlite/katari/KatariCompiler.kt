package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.model.ASTNode
import com.sunnychung.lib.multiplatform.kotlite.model.AssignmentNode
import com.sunnychung.lib.multiplatform.kotlite.model.BinaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.BreakNode
import com.sunnychung.lib.multiplatform.kotlite.model.BlockNode
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanNode
import com.sunnychung.lib.multiplatform.kotlite.model.ContinueNode
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallArgumentNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionTypeNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionValueParameterNode
import com.sunnychung.lib.multiplatform.kotlite.model.IfNode
import com.sunnychung.lib.multiplatform.kotlite.model.IndexOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.IntegerNode
import com.sunnychung.lib.multiplatform.kotlite.model.InfixFunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.LambdaLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCheckpointNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseEntryNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeJumpNode
import com.sunnychung.lib.multiplatform.kotlite.model.NavigationNode
import com.sunnychung.lib.multiplatform.kotlite.model.NullNode
import com.sunnychung.lib.multiplatform.kotlite.model.PropertyDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.ReturnNode
import com.sunnychung.lib.multiplatform.kotlite.model.ScriptNode
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.StringFieldIdentifierNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringNode
import com.sunnychung.lib.multiplatform.kotlite.model.UnaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.VariableReferenceNode
import com.sunnychung.lib.multiplatform.kotlite.model.ForNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionModifier
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionValueParameterModifier
import com.sunnychung.lib.multiplatform.kotlite.model.WhileNode
import kotlin.math.abs

class KatariCompiler(
    private val inlineEnvironmentFunctions: List<FunctionDeclarationNode> = emptyList(),
) {

    private var temporarySlotCounter: Int = 0
    private var lambdaCounter = 0
    private var nextFrameIdCounter: Int = ROOT_CALL_FRAME_ID
    private val loopContexts = ArrayDeque<LoopContext>()
    private val checkpointScopes = ArrayDeque<CheckpointScope>()
    private val userFunctions = mutableMapOf<String, MutableList<FunctionDeclarationNode>>()
    private val lambdaBindings = ArrayDeque<MutableMap<String, String?>>()
    private val frameIdStack = ArrayDeque<Int>()

    fun compile(script: ScriptNode): KatariProgram {
        temporarySlotCounter = 0
        lambdaCounter = 0
        nextFrameIdCounter = ROOT_CALL_FRAME_ID
        loopContexts.clear()
        checkpointScopes.clear()
        userFunctions.clear()
        lambdaBindings.clear()
        frameIdStack.clear()

        collectTopLevelUserFunctions(script)

        val instructions = mutableListOf<KatariInstruction>()
        compileStatementsInScope(script.nodes.filterNot { it is FunctionDeclarationNode }, instructions, isFunctionBoundary = true)
        instructions += EndInstruction(position = script.position)
        return KatariProgram(instructions = instructions)
    }

    private fun compileStatements(statements: List<ASTNode>, instructions: MutableList<KatariInstruction>) {
        statements.forEach { compileStatement(it, instructions) }
    }

    private fun compileStatementsInScope(
        statements: List<ASTNode>,
        instructions: MutableList<KatariInstruction>,
        isFunctionBoundary: Boolean = false,
        shadowedNames: Set<String> = emptySet(),
        lambdaParameterBindings: Map<String, String> = emptyMap(),
    ) {
        lambdaBindings.addLast((shadowedNames.associateWith { null } + lambdaParameterBindings).toMutableMap())
        checkpointScopes.addLast(CheckpointScope(isFunctionBoundary = isFunctionBoundary))
        if (isFunctionBoundary) {
            val newFrameId = nextFrameIdCounter++
            frameIdStack.addLast(newFrameId)
        }
        try {
            compileStatements(statements, instructions)
            val scope = checkpointScopes.last()
            val unresolvedToBubble = mutableListOf<UnresolvedJump>()
            scope.unresolved.forEach { jump ->
                val target = scope.checkpoints[jump.label]
                if (target != null) {
                    instructions[jump.instructionIndex] = JumpInstruction(
                        target = target,
                        position = instructions[jump.instructionIndex].position,
                    )
                } else {
                    unresolvedToBubble += jump
                }
            }
            if (unresolvedToBubble.isNotEmpty()) {
                val parentIndex = checkpointScopes.lastIndex - 1
                if (parentIndex >= 0 && !scope.isFunctionBoundary) {
                    checkpointScopes[parentIndex].unresolved += unresolvedToBubble
                } else {
                    val missing = unresolvedToBubble.first()
                    throw UnsupportedOperationException(
                        "${missing.position} Narrative jump target `${missing.label}` is not defined in this function scope"
                    )
                }
            }
        } finally {
            if (isFunctionBoundary) {
                frameIdStack.removeLast()
            }
            checkpointScopes.removeLast()
            lambdaBindings.removeLast()
        }
    }

    private fun compileStatement(statement: ASTNode, instructions: MutableList<KatariInstruction>) {
        when (statement) {
            is ScriptNode -> compileStatementsInScope(statement.nodes, instructions)
            is BlockNode -> compileStatementsInScope(statement.statements, instructions)
            is IfNode -> compileIf(statement, instructions)
            is WhileNode -> compileWhile(statement, instructions)
            is ForNode -> compileFor(statement, instructions)
            is PropertyDeclarationNode -> compilePropertyDeclaration(statement, instructions)
            is AssignmentNode -> compileAssignment(statement, instructions)
            is BreakNode -> compileBreak(statement, instructions)
            is ContinueNode -> compileContinue(statement, instructions)
            is NarrativeCheckpointNode -> compileCheckpoint(statement, instructions)
            is NarrativeJumpNode -> compileJump(statement, instructions)
            is NarrativeChooseNode -> compileChoose(statement, instructions)
            is FunctionDeclarationNode -> throw UnsupportedOperationException(
                "${statement.position} Local Katari function declarations are not supported. Declare functions at top-level only."
            )
            is UnaryOpNode -> compileUnaryStatement(statement, instructions)
            is StringLiteralNode -> instructions += CallFunctionInstruction(
                functionId = "narrate",
                arguments = listOf(LiteralExpression(KatariValue.Text(statement.content))),
                position = statement.position,
            )
            is StringNode -> instructions += CallFunctionInstruction(
                functionId = "narrate",
                arguments = listOf(compileStringExpression(statement, instructions)),
                position = statement.position,
            )
            is FunctionCallNode -> {
                if (!compileInlineCapableFunctionCall(statement, instructions)) {
                    instructions += compileCall(statement, instructions)
                }
            }
            else -> throw UnsupportedOperationException("${statement.position} Katari compiler does not support `${statement::class.simpleName}` yet")
        }
    }

    private fun collectTopLevelUserFunctions(script: ScriptNode) {
        script.nodes.filterIsInstance<FunctionDeclarationNode>().forEach { function ->
            validateUserFunctionDeclaration(function)
            val existing = userFunctions[function.name].orEmpty()
            require(existing.none { it.receiver == null && function.receiver == null }) {
                "${function.position} Katari function `${function.name}` is declared more than once"
            }
            require(existing.none { it.receiver != null && function.receiver != null }) {
                "${function.position} Katari extension function `${function.name}` is declared more than once"
            }
            userFunctions.getOrPut(function.name) { mutableListOf() } += function
        }
    }

    private fun validateUserFunctionDeclaration(function: FunctionDeclarationNode) {
        require(function.typeParameters.isEmpty() && function.extraTypeParameters.isEmpty()) {
            "${function.position} Katari user function `${function.name}` cannot declare type parameters"
        }
        require(function.body != null) {
            "${function.position} Katari user function `${function.name}` must have a body"
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
            "${function.position} Katari user function `${function.name}` can only use a single trailing return statement"
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
        instructions: MutableList<KatariInstruction>,
    ) {
        val currentScope = checkpointScopes.lastOrNull()
            ?: throw IllegalStateException("Checkpoint scope is not initialized")
        val label = node.label
        require(label !in currentScope.checkpoints) { "Checkpoint `$label` is declared more than once in this scope" }
        currentScope.checkpoints[label] = instructions.size
    }

    private fun compileJump(
        node: NarrativeJumpNode,
        instructions: MutableList<KatariInstruction>,
    ) {
        val currentScope = checkpointScopes.lastOrNull()
            ?: throw IllegalStateException("Checkpoint scope is not initialized")
        val label = node.label
        val target = findVisibleCheckpoint(label)
        if (target != null) {
            instructions += JumpInstruction(target = target, position = node.position)
        } else {
            currentScope.unresolved += UnresolvedJump(label = label, instructionIndex = instructions.size, node.position)
            instructions += JumpInstruction(target = -1, position = node.position)
        }
    }

    private fun findVisibleCheckpoint(label: String): Int? {
        checkpointScopes.toList().asReversed().forEach { scope ->
            val target = scope.checkpoints[label]
            if (target != null) {
                return target
            }
            if (scope.isFunctionBoundary) {
                return null
            }
        }
        return null
    }

    private fun compileChoose(
        node: NarrativeChooseNode,
        instructions: MutableList<KatariInstruction>,
    ) {
        require(node.entries.isNotEmpty()) { "Narrative choose block cannot be empty" }
        val choiceVariable = "__narrative_choose_${nextTemporarySlot()}"
        val compiledEntries = node.entries.mapIndexed { index, entry ->
            val indexText = index.toString()
            val optionTextExpression = compileExpression(entry.text, instructions)
            CompiledChooseEntry(
                entry = entry,
                argumentExpression = compileChooseEntryArgument(entry, optionTextExpression, instructions),
                selectedIdExpression = LiteralExpression(KatariValue.Text(indexText)),
            )
        }
        instructions += CallFunctionInstruction(
            functionId = "chooseIndexed",
            arguments = compiledEntries.map { it.argumentExpression },
            resultTarget = ResultTarget.Variable(choiceVariable, declaresLocal = true),
            position = node.position,
        )

        val endJumpIndices = mutableListOf<Int>()
        compiledEntries.forEachIndexed { index, compiledEntry ->
            val entry = compiledEntry.entry
            val condition = BinaryExpression(
                left = VariableExpression(choiceVariable, position = entry.position),
                operator = BinaryOperator.Equals,
                right = compiledEntry.selectedIdExpression,
                position = entry.position,
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
        textExpression: KatariExpression,
        instructions: MutableList<KatariInstruction>,
    ): KatariExpression {
        if (entry.visibleCondition == null && entry.disableCondition == null) {
            return textExpression
        }
        val slot = nextTemporarySlot()
        val visibleExpression = entry.visibleCondition?.let { compileExpression(it, instructions) }
            ?: LiteralExpression(KatariValue.Bool(true))
        val enabledExpression = entry.disableCondition?.let {
            UnaryExpression(
                operator = UnaryOperator.Not,
                operand = compileExpression(it, instructions),
                position = entry.position,
            )
        } ?: LiteralExpression(KatariValue.Bool(true))
        val disabledTextExpression = entry.disabledText?.let { compileExpression(it, instructions) }
            ?: LiteralExpression(KatariValue.Null)
        instructions += CallFunctionInstruction(
            functionId = "choiceOption",
            arguments = listOf(
                textExpression,
                visibleExpression,
                enabledExpression,
                disabledTextExpression,
            ),
            resultTarget = ResultTarget.Slot(slot),
            position = entry.position,
        )
        return SlotExpression(slot)
    }

    private fun compilePropertyDeclaration(node: PropertyDeclarationNode, instructions: MutableList<KatariInstruction>) {
        val initialValue = node.initialValue
            ?: throw UnsupportedOperationException("Katari property `${node.name}` requires an initializer")
        val targetName = resolveVariableName(node.name)
        when {
            initialValue is LambdaLiteralNode -> {
                val lambdaId = registerLambda(initialValue)
                bindVariableLambda(node.name, if (node.isMutable) null else lambdaId)
                instructions += SetVariableInstruction(
                    name = targetName,
                    expression = LambdaLiteralExpression(lambdaId = lambdaId, position = initialValue.position),
                    declaresLocal = true,
                    position = node.position,
                )
                return
            }
            else -> bindVariableLambda(node.name, null)
        }
        if (initialValue is FunctionCallNode) {
            val invocation = resolveInlineCapableFunctionInvocation(initialValue, instructions)
            if (invocation != null) {
                compileUserFunctionInvocation(
                    declaration = invocation.declaration,
                    callPosition = initialValue.position,
                    argumentExpressions = invocation.argumentExpressions,
                    instructions = instructions,
                    resultTarget = ResultTarget.Variable(targetName, declaresLocal = true),
                    receiverExpression = invocation.receiverExpression,
                )
            } else {
                instructions += compileCall(
                    node = initialValue,
                    instructions = instructions,
                    resultTarget = ResultTarget.Variable(targetName, declaresLocal = true),
                )
            }
            return
        }
        instructions += SetVariableInstruction(
            name = targetName,
            expression = compileExpression(initialValue, instructions),
            declaresLocal = true,
            position = node.position,
        )
    }

    private fun compileAssignment(node: AssignmentNode, instructions: MutableList<KatariInstruction>) {
        when (node.operator) {
            "=" -> {
                when (val target = node.subject) {
                    is VariableReferenceNode -> {
                        val targetName = resolveVariableName(target.variableName)
                        bindVariableLambda(target.variableName, null)
                        if (node.value is FunctionCallNode) {
                            val invocation = resolveInlineCapableFunctionInvocation(node.value, instructions)
                            if (invocation != null) {
                                compileUserFunctionInvocation(
                                    declaration = invocation.declaration,
                                    callPosition = node.value.position,
                                    argumentExpressions = invocation.argumentExpressions,
                                    instructions = instructions,
                                    resultTarget = ResultTarget.Variable(targetName),
                                    receiverExpression = invocation.receiverExpression,
                                )
                            } else {
                                instructions += compileCall(
                                    node = node.value,
                                    instructions = instructions,
                                    resultTarget = ResultTarget.Variable(targetName),
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
                    is IndexOpNode -> {
                        val declaration = resolveCallableDeclaration("set", requiresReceiver = true)
                        val receiverExpression = compileExpression(target.subject, instructions)
                        val argumentExpressions = buildList {
                            target.arguments.forEach { add(compileExpression(it, instructions)) }
                            add(compileExpression(node.value, instructions))
                        }
                        if (declaration != null) {
                            compileUserFunctionInvocation(
                                declaration = declaration,
                                callPosition = node.position,
                                argumentExpressions = argumentExpressions,
                                instructions = instructions,
                                resultTarget = null,
                                receiverExpression = receiverExpression,
                            )
                        } else {
                            instructions += CallFunctionInstruction(
                                functionId = "set",
                                arguments = listOf(receiverExpression) + argumentExpressions,
                                position = node.position,
                            )
                        }
                    }
                    is NavigationNode -> {
                        instructions += CallFunctionInstruction(
                            functionId = target.member.name,
                            arguments = listOf(
                                compileExpression(target.subject, instructions),
                                compileExpression(node.value, instructions),
                            ),
                            position = node.position,
                        )
                    }
                    else -> throw UnsupportedOperationException(
                        "Katari assignment target `${target::class.simpleName}` is not supported"
                    )
                }
            }
            "+=", "-=" -> {
                val target = node.subject as? VariableReferenceNode
                    ?: throw UnsupportedOperationException("Katari assignment target must be a variable reference")
                val targetName = resolveVariableName(target.variableName)
                val operator = if (node.operator == "+=") BinaryOperator.Add else BinaryOperator.Subtract
                instructions += SetVariableInstruction(
                    name = targetName,
                    expression = BinaryExpression(
                        left = VariableExpression(targetName),
                        operator = operator,
                        right = compileExpression(node.value, instructions),
                        position = node.position,
                    ),
                    position = node.position,
                )
            }
            else -> throw UnsupportedOperationException("e${node.position}: Katari assignment `${node.operator}` is not supported yet")
        }
    }

    private fun compileIfExpression(node: IfNode, instructions: MutableList<KatariInstruction>): KatariExpression {
        val slot = nextTemporarySlot()
        val target = ResultTarget.Slot(slot)
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
        target: ResultTarget,
        instructions: MutableList<KatariInstruction>,
    ) {
        val statements = block?.statements ?: listOf(NullNode)
        if (statements.isEmpty()) {
            instructions += SetResultInstruction(target, LiteralExpression(KatariValue.Null), block?.position)
            return
        }
        statements.dropLast(1).forEach { compileStatement(it, instructions) }
        compileExpressionIntoTarget(statements.last(), target, instructions)
    }

    private fun compileExpressionIntoTarget(
        expression: ASTNode,
        target: ResultTarget,
        instructions: MutableList<KatariInstruction>,
    ) {
        if (expression is FunctionCallNode) {
            val invocation = resolveInlineCapableFunctionInvocation(expression, instructions)
            if (invocation != null) {
                compileUserFunctionInvocation(
                    declaration = invocation.declaration,
                    callPosition = expression.position,
                    argumentExpressions = invocation.argumentExpressions,
                    instructions = instructions,
                    resultTarget = target,
                    receiverExpression = invocation.receiverExpression,
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

    private fun compileWhile(node: WhileNode, instructions: MutableList<KatariInstruction>) {
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
        patchContinueJumps(loopContext, instructions)
        loopContext.breakJumpIndices.forEach { breakIndex ->
            instructions[breakIndex] = JumpInstruction(target = loopExit, position = instructions[breakIndex].position)
        }
        loopContexts.removeLast()
    }

    private fun compileFor(node: ForNode, instructions: MutableList<KatariInstruction>) {
        val iteratorName = "__narrative_for_iterator_${nextTemporarySlot()}"
        val valueName = "__narrative_for_value_${nextTemporarySlot()}"
        val loopVariableNames = node.variables.map { resolveVariableName(it.name) }
        loopVariableNames.forEach { bindVariableLambda(it, null) }
        instructions += CallFunctionInstruction(
            functionId = "iterator",
            arguments = listOf(compileExpression(node.subject, instructions)),
            resultTarget = ResultTarget.Variable(iteratorName, declaresLocal = true),
            position = node.position,
        )

        val loopStart = instructions.size
        val loopContext = LoopContext()
        loopContexts.addLast(loopContext)
        val hasNextSlot = nextTemporarySlot()
        instructions += CallFunctionInstruction(
            functionId = "hasNext",
            arguments = listOf(VariableExpression(iteratorName, position = node.position)),
            resultTarget = ResultTarget.Slot(hasNextSlot),
            position = node.position,
        )
        val conditionalIndex = instructions.size
        instructions += ConditionalJumpInstruction(
            condition = SlotExpression(hasNextSlot, position = node.position),
            falseTarget = -1,
            position = node.position,
        )
        instructions += CallFunctionInstruction(
            functionId = "next",
            arguments = listOf(VariableExpression(iteratorName, position = node.position)),
            resultTarget = ResultTarget.Variable(valueName, declaresLocal = true),
            position = node.position,
        )
        loopVariableNames.forEach { variableName ->
            instructions += SetVariableInstruction(
                name = variableName,
                expression = VariableExpression(valueName, position = node.position),
                position = node.position,
            )
        }
        node.body.let { compileStatement(it, instructions) }
        val continueCleanupTarget = instructions.size
        loopContext.continueTarget = continueCleanupTarget
        patchContinueJumps(loopContext, instructions)
        instructions += RemoveVariablesInstruction(
            names = loopVariableNames + valueName,
            position = node.position,
        )
        instructions += JumpInstruction(target = loopStart, position = node.position)
        val breakCleanupTarget = instructions.size
        instructions[conditionalIndex] = ConditionalJumpInstruction(
            condition = SlotExpression(hasNextSlot, position = node.position),
            falseTarget = breakCleanupTarget,
            position = node.position,
        )
        loopContext.breakJumpIndices.forEach { breakIndex ->
            instructions[breakIndex] = JumpInstruction(
                target = breakCleanupTarget,
                position = instructions[breakIndex].position,
            )
        }
        instructions += RemoveVariablesInstruction(
            names = loopVariableNames + valueName + iteratorName,
            position = node.position,
        )
        loopContexts.removeLast()
    }

    private fun compileBreak(node: BreakNode, instructions: MutableList<KatariInstruction>) {
        val loopContext = loopContexts.lastOrNull()
            ?: throw UnsupportedOperationException("Katari `break` can only be used inside a loop")
        loopContext.breakJumpIndices += instructions.size
        instructions += JumpInstruction(target = -1, position = node.position)
    }

    private fun compileContinue(node: ContinueNode, instructions: MutableList<KatariInstruction>) {
        val loopContext = loopContexts.lastOrNull()
            ?: throw UnsupportedOperationException("Katari `continue` can only be used inside a loop")
        val continueTarget = loopContext.continueTarget
        if (continueTarget != null) {
            instructions += JumpInstruction(target = continueTarget, position = node.position)
        } else {
            loopContext.continueJumpIndices += instructions.size
            instructions += JumpInstruction(target = -1, position = node.position)
        }
    }

    private fun patchContinueJumps(
        loopContext: LoopContext,
        instructions: MutableList<KatariInstruction>,
    ) {
        val continueTarget = loopContext.continueTarget ?: return
        loopContext.continueJumpIndices.forEach { continueIndex ->
            instructions[continueIndex] = JumpInstruction(
                target = continueTarget,
                position = instructions[continueIndex].position,
            )
        }
    }

    private fun compileIf(node: IfNode, instructions: MutableList<KatariInstruction>) {
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

    private fun compileUnaryStatement(node: UnaryOpNode, instructions: MutableList<KatariInstruction>) {
        when (node.operator) {
            "pre++", "post++", "pre--", "post--" -> {
                compileIncDecExpression(node, instructions)
            }
            else -> throw UnsupportedOperationException(
                "Katari unary statement `${node.operator}` is not supported"
            )
        }
    }

    private fun compileCall(
        node: FunctionCallNode,
        instructions: MutableList<KatariInstruction>,
        resultTarget: ResultTarget? = null,
    ): KatariInstruction {
        val arguments = node.arguments.map { compileExpression(it.value, instructions) }
        require(arguments.none { it is LambdaLiteralExpression }) {
            "${node.position} Passing lambda arguments to external narrative functions is not supported yet"
        }
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
            else -> throw UnsupportedOperationException("Katari call `${function::class.simpleName}` is not supported")
        }
    }

    private fun compileUserFunctionInvocation(
        declaration: FunctionDeclarationNode,
        callPosition: SourcePosition,
        argumentExpressions: List<KatariExpression>,
        instructions: MutableList<KatariInstruction>,
        resultTarget: ResultTarget?,
        receiverExpression: KatariExpression? = null,
    ) {
        val functionName = declaration.name
        require(argumentExpressions.size == declaration.valueParameters.size) {
            "$callPosition Katari user function `${functionName}` expects ${declaration.valueParameters.size} arguments but got ${argumentExpressions.size}"
        }
        require((declaration.receiver != null) == (receiverExpression != null)) {
            if (declaration.receiver != null) {
                "$callPosition Katari extension function `${functionName}` requires a receiver"
            } else {
                "$callPosition Katari function `${functionName}` does not accept a receiver"
            }
        }

        val body = declaration.body
            ?: throw UnsupportedOperationException("${declaration.position} Katari user function `${functionName}` must have a body")

        val parameterNames = declaration.valueParameters.map { it.name }
        val lambdaParameterBindings = parameterNames.mapIndexedNotNull { index, parameterName ->
            (argumentExpressions[index] as? LambdaLiteralExpression)?.let { parameterName to it.lambdaId }
        }.toMap()
        val lexicalParentFrameId = frameIdStack.lastOrNull()
        instructions += EnterCallFrameInstruction(
            functionId = functionName,
            lexicalParentFrameId = lexicalParentFrameId,
            position = callPosition,
        )
        receiverExpression?.let {
            instructions += SetVariableInstruction(
                name = "this",
                expression = it,
                position = callPosition,
            )
        }
        parameterNames.forEachIndexed { index, parameterName ->
            instructions += SetVariableInstruction(
                name = parameterName,
                expression = argumentExpressions[index],
                position = callPosition,
            )
        }
        if (resultTarget == null) {
            validateUserFunctionBodyAsStatements(body, functionName)
            val trailingReturnValue = (body.statements.lastOrNull() as? ReturnNode)?.value
            compileStatementsInScope(
                if (trailingReturnValue != null) body.statements.dropLast(1) else body.statements,
                instructions,
                isFunctionBoundary = true,
                shadowedNames = parameterNames.toSet() + setOfNotNull(receiverExpression?.let { "this" }),
                lambdaParameterBindings = lambdaParameterBindings,
            )
            trailingReturnValue?.let {
                compileExpressionIntoTarget(
                    expression = it,
                    target = ResultTarget.Slot(nextTemporarySlot()),
                    instructions = instructions,
                )
            }
            instructions += ExitCallFrameInstruction(
                returnExpression = null,
                resultTarget = null,
                position = callPosition,
            )
        } else {
            val returnName = "__narrative_return_${nextTemporarySlot()}"
            compileStatementsInScope(
                body.statements.dropLast(1),
                instructions,
                isFunctionBoundary = true,
                shadowedNames = parameterNames.toSet() + setOfNotNull(receiverExpression?.let { "this" }),
                lambdaParameterBindings = lambdaParameterBindings,
            )
            validateUserFunctionBodyAsExpression(body, functionName)
            val last = body.statements.last()
            when (last) {
                is ReturnNode -> {
                    val returnValue = last.value ?: NullNode
                    compileExpressionIntoTarget(returnValue, ResultTarget.Variable(returnName, declaresLocal = true), instructions)
                }
                else -> compileExpressionIntoTarget(last, ResultTarget.Variable(returnName, declaresLocal = true), instructions)
            }
            instructions += ExitCallFrameInstruction(
                returnExpression = VariableExpression(returnName, position = last.position),
                resultTarget = resultTarget,
                position = callPosition,
            )
        }
    }

    private fun validateUserFunctionBodyAsStatements(
        body: BlockNode,
        functionName: String,
    ) {
        val statements = body.statements
        if (statements.isEmpty()) return
        statements.forEachIndexed { index, statement ->
            if (statement is ReturnNode) {
                require(index == statements.lastIndex) {
                    "${statement.position} Katari user function `${functionName}` only supports trailing return"
                }
                return
            }
        }
    }

    private fun validateUserFunctionBodyAsExpression(
        body: BlockNode,
        functionName: String,
    ) {
        val statements = body.statements
        require(statements.isNotEmpty()) {
            "${body.position} Katari user function `${functionName}` must have a non-empty body to return a value"
        }

        statements.dropLast(1).forEach { statement ->
            require(statement !is ReturnNode) {
                "${statement.position} Katari user function `${functionName}` only supports trailing return"
            }
        }
    }

    private fun resolveVariableName(name: String): String {
        return name
    }

    private fun compileInlineCapableFunctionCall(
        node: FunctionCallNode,
        instructions: MutableList<KatariInstruction>,
    ): Boolean {
        val invocation = resolveInlineCapableFunctionInvocation(node, instructions) ?: return false
        compileUserFunctionInvocation(
            declaration = invocation.declaration,
            callPosition = node.position,
            argumentExpressions = invocation.argumentExpressions,
            instructions = instructions,
            resultTarget = null,
            receiverExpression = invocation.receiverExpression,
        )
        return true
    }

    private fun resolveInlineCapableFunctionInvocation(
        node: FunctionCallNode,
        instructions: MutableList<KatariInstruction>,
    ): ResolvedUserFunctionInvocation? {
        return resolveUserFunctionInvocation(node, instructions)
            ?: resolveEnvironmentInlineFunctionInvocation(node, instructions)
    }

    private fun resolveUserFunctionInvocation(
        node: FunctionCallNode,
        instructions: MutableList<KatariInstruction>,
    ): ResolvedUserFunctionInvocation? {
        return when (val function = node.function) {
            is VariableReferenceNode -> {
                val declaration = resolveCallableDeclaration(function.variableName, requiresReceiver = false) ?: return null
                ResolvedUserFunctionInvocation(
                    declaration = declaration,
                    receiverExpression = null,
                    argumentExpressions = node.arguments.map { compileExpression(it.value, instructions) },
                )
            }
            is NavigationNode -> {
                val declaration = resolveCallableDeclaration(function.member.name, requiresReceiver = true) ?: return null
                ResolvedUserFunctionInvocation(
                    declaration = declaration,
                    receiverExpression = compileExpression(function.subject, instructions),
                    argumentExpressions = node.arguments.map { compileExpression(it.value, instructions) },
                )
            }
            else -> null
        }
    }

    private fun compileExpression(expression: ASTNode, instructions: MutableList<KatariInstruction>): KatariExpression {
        return when (expression) {
            is BooleanNode -> LiteralExpression(KatariValue.Bool(expression.value), position = expression.position)
            is IntegerNode -> LiteralExpression(KatariValue.Int32(expression.value), position = expression.position)
            is DoubleNode -> LiteralExpression(KatariValue.Float64(expression.value), position = expression.position)
            NullNode -> LiteralExpression(KatariValue.Null, position = expression.position)
            is StringLiteralNode -> LiteralExpression(KatariValue.Text(expression.content), position = expression.position)
            is StringNode -> compileStringExpression(expression, instructions)
            is StringFieldIdentifierNode -> VariableExpression(resolveVariableName(expression.variableName), position = expression.position)
            is VariableReferenceNode -> VariableExpression(resolveVariableName(expression.variableName), position = expression.position)
            is LambdaLiteralNode -> {
                val lambdaId = registerLambda(expression)
                LambdaLiteralExpression(lambdaId = lambdaId, position = expression.position)
            }
            is IfNode -> compileIfExpression(expression, instructions)
            is FunctionCallNode -> {
                val invocation = resolveInlineCapableFunctionInvocation(expression, instructions)
                if (invocation != null) {
                    val slot = nextTemporarySlot()
                    compileUserFunctionInvocation(
                        declaration = invocation.declaration,
                        callPosition = expression.position,
                        argumentExpressions = invocation.argumentExpressions,
                        instructions = instructions,
                        resultTarget = ResultTarget.Slot(slot),
                        receiverExpression = invocation.receiverExpression,
                    )
                    return SlotExpression(slot, position = expression.position)
                }
                val slot = nextTemporarySlot()
                instructions += compileCall(
                    node = expression,
                    instructions = instructions,
                    resultTarget = ResultTarget.Slot(slot),
                )
                SlotExpression(slot, position = expression.position)
            }
            is NavigationNode -> compileExternalExpressionCall(
                functionId = expression.member.name,
                arguments = listOf(compileExpression(expression.subject, instructions)),
                position = expression.position,
                instructions = instructions,
            )
            is IndexOpNode -> compileExternalExpressionCall(
                functionId = "get",
                arguments = listOf(compileExpression(expression.subject, instructions)) +
                    expression.arguments.map { compileExpression(it, instructions) },
                position = expression.position,
                instructions = instructions,
            )
            is InfixFunctionCallNode -> {
                when (expression.functionName) {
                    "to" -> compileExternalExpressionCall(
                        functionId = "Pair",
                        arguments = listOf(
                            compileExpression(expression.node1, instructions),
                            compileExpression(expression.node2, instructions),
                        ),
                        position = expression.position,
                        instructions = instructions,
                    )
                    "is", "!is", "in", "!in" -> throw UnsupportedOperationException(
                        "Infix operator `${expression.functionName}` is not supported in Katari expressions"
                    )
                    else -> {
                        val slot = nextTemporarySlot()
                        instructions += CallFunctionInstruction(
                            functionId = expression.functionName,
                            arguments = listOf(
                                compileExpression(expression.node1, instructions),
                                compileExpression(expression.node2, instructions),
                            ),
                            resultTarget = ResultTarget.Slot(slot),
                            position = expression.position,
                        )
                        SlotExpression(slot, position = expression.position)
                    }
                }
            }
            is UnaryOpNode -> {
                when (expression.operator) {
                    "pre++", "post++", "pre--", "post--" -> compileIncDecExpression(expression, instructions)
                    else -> {
                        val operator = when (expression.operator) {
                            "+" -> UnaryOperator.Plus
                            "-" -> UnaryOperator.Minus
                            "!" -> UnaryOperator.Not
                            else -> throw UnsupportedOperationException(
                                "Unary operator `${expression.operator}` is not supported in Katari expressions"
                            )
                        }
                        val operand = expression.node
                            ?: throw UnsupportedOperationException("Unary operator `${expression.operator}` requires an operand")
                        UnaryExpression(
                            operator = operator,
                            operand = compileExpression(operand, instructions),
                            position = expression.position,
                        )
                    }
                }
            }
            is BinaryOpNode -> {
                val operator = when (expression.operator) {
                    "+" -> BinaryOperator.Add
                    "-" -> BinaryOperator.Subtract
                    "*" -> BinaryOperator.Multiply
                    "/" -> BinaryOperator.Divide
                    "%" -> BinaryOperator.Remainder
                    "<" -> BinaryOperator.LessThan
                    "<=" -> BinaryOperator.LessThanOrEquals
                    ">" -> BinaryOperator.GreaterThan
                    ">=" -> BinaryOperator.GreaterThanOrEquals
                    "==" -> BinaryOperator.Equals
                    "!=" -> BinaryOperator.NotEquals
                    "&&" -> BinaryOperator.And
                    "||" -> BinaryOperator.Or
                    else -> throw UnsupportedOperationException("Binary operator `${expression.operator}` is not supported in Katari expressions")
                }
                BinaryExpression(
                    left = compileExpression(expression.node1, instructions),
                    operator = operator,
                    right = compileExpression(expression.node2, instructions),
                    position = expression.position,
                )
            }
            else -> throw UnsupportedOperationException("${expression.position} Katari expression `${expression::class.simpleName}` is not supported")
        }
    }

    private fun compileExternalExpressionCall(
        functionId: String,
        arguments: List<KatariExpression>,
        position: SourcePosition,
        instructions: MutableList<KatariInstruction>,
    ): KatariExpression {
        val slot = nextTemporarySlot()
        instructions += CallFunctionInstruction(
            functionId = functionId,
            arguments = arguments,
            resultTarget = ResultTarget.Slot(slot),
            position = position,
        )
        return SlotExpression(slot, position = position)
    }

    private fun compileArgumentExpression(
        argument: FunctionCallArgumentNode,
        expectedParameter: FunctionValueParameterNode?,
        instructions: MutableList<KatariInstruction>,
    ): KatariExpression {
        val expectedType = expectedParameter?.type as? FunctionTypeNode
        val value = argument.value
        if (value is LambdaLiteralNode && expectedType != null) {
            val lambdaId = registerLambda(value, expectedType)
            return LambdaLiteralExpression(lambdaId = lambdaId, position = value.position)
        }
        return compileExpression(value, instructions)
    }

    private fun registerLambda(lambda: LambdaLiteralNode, expectedType: FunctionTypeNode? = null): String {
        val expectedParameterTypes = expectedType?.parameterTypes.orEmpty()
        val explicitValueParameters = lambda.valueParameters
        val valueParametersWithExpectedTypes = if (
            expectedType != null &&
            explicitValueParameters.isEmpty() &&
            expectedParameterTypes.size == 1
        ) {
            listOf(
                FunctionValueParameterNode(
                    position = lambda.position,
                    name = "it",
                    declaredType = expectedParameterTypes.single(),
                    defaultValue = null,
                    modifiers = emptySet(),
                )
            )
        } else {
            explicitValueParameters
        }
        if (expectedType != null && expectedParameterTypes.size != valueParametersWithExpectedTypes.size) {
            throw IllegalArgumentException(
                "${lambda.position} Katari lambda expects ${expectedParameterTypes.size} parameter(s), got ${valueParametersWithExpectedTypes.size}"
            )
        }
        val valueParameters = valueParametersWithExpectedTypes.mapIndexed { index, parameter ->
            if (parameter.declaredType == null && expectedType != null) {
                parameter.copy(declaredType = expectedParameterTypes[index])
            } else {
                parameter
            }
        }
        valueParameters.forEach { parameter ->
            require(parameter.declaredType != null) {
                "${parameter.position} Katari lambda parameter `${parameter.name}` must declare explicit type"
            }
        }
        val id = "__narrative_lambda_${lambdaCounter++}"
        val declaration = FunctionDeclarationNode(
            position = lambda.position,
            name = id,
            receiver = null,
            declaredReturnType = null,
            valueParameters = valueParameters,
            body = lambda.body,
        )
        validateUserFunctionDeclaration(declaration)
        userFunctions[id] = mutableListOf(declaration)
        return id
    }

    private fun bindVariableLambda(name: String, lambdaId: String?) {
        val currentScope = lambdaBindings.lastOrNull()
            ?: throw IllegalStateException("Lambda binding scope is not initialized")
        currentScope[name] = lambdaId
    }

    private fun resolveLambdaBinding(name: String): String? {
        lambdaBindings.toList().asReversed().forEach { scope ->
            if (name in scope) {
                return scope[name]
            }
        }
        return null
    }

    private fun resolveCallableDeclaration(name: String, requiresReceiver: Boolean? = null): FunctionDeclarationNode? {
        userFunctions[name]
            ?.firstOrNull { declaration ->
                requiresReceiver == null || (declaration.receiver != null) == requiresReceiver
            }
            ?.let { return it }
        val lambdaId = resolveLambdaBinding(name) ?: return null
        return userFunctions[lambdaId]?.firstOrNull()
    }

    private fun resolveEnvironmentInlineFunctionInvocation(
        node: FunctionCallNode,
        instructions: MutableList<KatariInstruction>,
    ): ResolvedUserFunctionInvocation? {
        return when (val function = node.function) {
            is VariableReferenceNode -> {
                val declaration = inlineEnvironmentFunctions.firstOrNull {
                    it.name == function.variableName &&
                        it.receiver == null &&
                        FunctionModifier.inline in it.modifiers &&
                        it.body != null &&
                        matchesArgumentShape(it, node.arguments.size)
                } ?: return null
                ResolvedUserFunctionInvocation(
                    declaration = declaration,
                    receiverExpression = null,
                    argumentExpressions = node.arguments.mapIndexed { index, argument ->
                        compileArgumentExpression(argument, declaration.valueParameters.getOrNull(index), instructions)
                    },
                )
            }
            is NavigationNode -> {
                val declaration = inlineEnvironmentFunctions.firstOrNull {
                    it.name == function.member.name &&
                        it.receiver != null &&
                        FunctionModifier.inline in it.modifiers &&
                        it.body != null &&
                        matchesArgumentShape(it, node.arguments.size)
                } ?: return null
                ResolvedUserFunctionInvocation(
                    declaration = declaration,
                    receiverExpression = compileExpression(function.subject, instructions),
                    argumentExpressions = node.arguments.mapIndexed { index, argument ->
                        compileArgumentExpression(argument, declaration.valueParameters.getOrNull(index), instructions)
                    },
                )
            }
            else -> null
        }
    }

    private fun matchesArgumentShape(
        declaration: FunctionDeclarationNode,
        argumentCount: Int,
    ): Boolean {
        return if (declaration.isNarrativeVararg()) {
            declaration.valueParameters.size <= 1
        } else {
            declaration.valueParameters.size == argumentCount
        }
    }

    private fun compileStringExpression(
        node: StringNode,
        instructions: MutableList<KatariInstruction>,
    ): KatariExpression {
        if (node.nodes.size == 1 && node.nodes.first() is StringLiteralNode) {
            return compileExpression(node.nodes.first(), instructions)
        }
        return node.nodes
            .map { part -> compileExpression(part, instructions) }
            .reduce { acc, part ->
                BinaryExpression(
                    left = acc,
                    operator = BinaryOperator.Add,
                    right = part,
                    position = node.position,
                )
            }
    }

    private fun compileIncDecExpression(
        node: UnaryOpNode,
        instructions: MutableList<KatariInstruction>,
    ): KatariExpression {
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
            operator = if (delta > 0) BinaryOperator.Add else BinaryOperator.Subtract,
            right = LiteralExpression(KatariValue.Int32(abs(delta))),
            position = node.position,
        )
        return when (node.operator) {
            "pre++", "pre--" -> {
                instructions += SetVariableInstruction(
                    name = variableName,
                    expression = updateExpression,
                    position = node.position,
                )
                VariableExpression(variableName, position = node.position)
            }
            "post++", "post--" -> {
                val slot = nextTemporarySlot()
                instructions += SetResultInstruction(
                    target = ResultTarget.Slot(slot),
                    expression = BinaryExpression(
                        left = VariableExpression(variableName),
                        operator = BinaryOperator.Add,
                        right = LiteralExpression(KatariValue.Int32(0)),
                        position = node.position,
                    ),
                    position = node.position,
                )
                instructions += SetVariableInstruction(
                    name = variableName,
                    expression = updateExpression,
                    position = node.position,
                )
                SlotExpression(slot, position = node.position)
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
    var continueTarget: Int? = null,
    val continueJumpIndices: MutableList<Int> = mutableListOf(),
    val breakJumpIndices: MutableList<Int> = mutableListOf(),
)

private data class CheckpointScope(
    val checkpoints: MutableMap<String, Int> = mutableMapOf(),
    val unresolved: MutableList<UnresolvedJump> = mutableListOf(),
    val isFunctionBoundary: Boolean = false,
)

private data class UnresolvedJump(
    val label: String,
    val instructionIndex: Int,
    val position: SourcePosition,
)

private data class CompiledChooseEntry(
    val entry: NarrativeChooseEntryNode,
    val argumentExpression: KatariExpression,
    val selectedIdExpression: KatariExpression,
)

private data class ResolvedUserFunctionInvocation(
    val declaration: FunctionDeclarationNode,
    val receiverExpression: KatariExpression?,
    val argumentExpressions: List<KatariExpression>,
)

private fun FunctionDeclarationNode.isNarrativeVararg(): Boolean {
    return isVararg ||
        valueParameters.firstOrNull()?.modifiers?.contains(FunctionValueParameterModifier.vararg) == true
}
