package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.model.ASTNode
import com.sunnychung.lib.multiplatform.kotlite.model.AssignmentNode
import com.sunnychung.lib.multiplatform.kotlite.model.BinaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.BlockNode
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.IfNode
import com.sunnychung.lib.multiplatform.kotlite.model.IntegerNode
import com.sunnychung.lib.multiplatform.kotlite.model.NavigationNode
import com.sunnychung.lib.multiplatform.kotlite.model.PropertyDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.ScriptNode
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.StringLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringNode
import com.sunnychung.lib.multiplatform.kotlite.model.VariableReferenceNode
import com.sunnychung.lib.multiplatform.kotlite.model.WhileNode

class NarrativeCompiler {

    private var temporarySlotCounter: Int = 0

    fun compile(script: ScriptNode): NarrativeProgram {
        temporarySlotCounter = 0
        val instructions = mutableListOf<NarrativeInstruction>()
        compileStatements(script.nodes, instructions)
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
            is FunctionCallNode -> instructions += compileCall(statement, instructions)
            else -> throw UnsupportedOperationException("Narrative compiler does not support `${statement::class.simpleName}` yet")
        }
    }

    private fun compilePropertyDeclaration(node: PropertyDeclarationNode, instructions: MutableList<NarrativeInstruction>) {
        val initialValue = node.initialValue
            ?: throw UnsupportedOperationException("Narrative property `${node.name}` requires an initializer")
        if (initialValue is FunctionCallNode) {
            instructions += compileCall(
                node = initialValue,
                instructions = instructions,
                resultTarget = NarrativeResultTarget.Variable(node.name),
            )
            return
        }
        instructions += SetVariableInstruction(
            name = node.name,
            expression = compileExpression(initialValue, instructions),
            position = node.position,
        )
    }

    private fun compileAssignment(node: AssignmentNode, instructions: MutableList<NarrativeInstruction>) {
        require(node.operator == "=") {
            "Narrative assignment currently supports only `=`"
        }
        val target = node.subject as? VariableReferenceNode
            ?: throw UnsupportedOperationException("Narrative assignment target must be a variable reference")
        if (node.value is FunctionCallNode) {
            instructions += compileCall(
                node = node.value,
                instructions = instructions,
                resultTarget = NarrativeResultTarget.Variable(target.variableName),
            )
            return
        }
        instructions += SetVariableInstruction(
            name = target.variableName,
            expression = compileExpression(node.value, instructions),
            position = node.position,
        )
    }

    private fun compileWhile(node: WhileNode, instructions: MutableList<NarrativeInstruction>) {
        val loopStart = instructions.size
        val condition = compileExpression(node.condition, instructions)
        val conditionalIndex = instructions.size
        instructions += ConditionalJumpInstruction(
            condition = condition,
            falseTarget = -1,
            position = node.position,
        )
        node.body?.let { compileStatement(it, instructions) }
        instructions += JumpInstruction(target = loopStart, position = node.position)
        instructions[conditionalIndex] = ConditionalJumpInstruction(
            condition = condition,
            falseTarget = instructions.size,
            position = node.position,
        )
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

    private fun compileExpression(expression: ASTNode, instructions: MutableList<NarrativeInstruction>): NarrativeExpression {
        return when (expression) {
            is BooleanNode -> LiteralExpression(NarrativeValue.Bool(expression.value))
            is IntegerNode -> LiteralExpression(NarrativeValue.Int32(expression.value))
            is StringLiteralNode -> LiteralExpression(NarrativeValue.Text(expression.content))
            is StringNode -> compileStringExpression(expression, instructions)
            is VariableReferenceNode -> VariableExpression(expression.variableName)
            is FunctionCallNode -> {
                val slot = nextTemporarySlot()
                instructions += compileCall(
                    node = expression,
                    instructions = instructions,
                    resultTarget = NarrativeResultTarget.Slot(slot),
                )
                SlotExpression(slot)
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
            else -> throw UnsupportedOperationException("Narrative expression `${expression::class.simpleName}` is not supported")
        }
    }

    private fun compileStringExpression(
        node: StringNode,
        instructions: MutableList<NarrativeInstruction>,
    ): NarrativeExpression {
        if (node.nodes.size == 1 && node.nodes.first() is StringLiteralNode) {
            return compileExpression(node.nodes.first(), instructions)
        }
        val parts = node.nodes.map { part ->
            when (part) {
                is StringLiteralNode -> part.content
                else -> throw UnsupportedOperationException("Interpolated narrative strings are not supported yet at ${formatPosition(node.position)}")
            }
        }
        return LiteralExpression(NarrativeValue.Text(parts.joinToString(separator = "")))
    }

    private fun nextTemporarySlot(): Int {
        val slot = temporarySlotCounter
        temporarySlotCounter += 1
        return slot
    }

    private fun formatPosition(position: SourcePosition): String {
        return "${position.filename}:${position.lineNum}:${position.col}"
    }
}
