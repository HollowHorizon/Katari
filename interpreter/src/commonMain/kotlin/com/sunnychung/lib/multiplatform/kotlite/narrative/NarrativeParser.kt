package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.Parser
import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.model.ASTNode
import com.sunnychung.lib.multiplatform.kotlite.model.BinaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.BlockNode
import com.sunnychung.lib.multiplatform.kotlite.model.ElvisOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionBodyFormat
import com.sunnychung.lib.multiplatform.kotlite.model.InfixFunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCheckpointNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseEntryNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeJumpNode
import com.sunnychung.lib.multiplatform.kotlite.model.ScopeType
import com.sunnychung.lib.multiplatform.kotlite.model.ScriptNode
import com.sunnychung.lib.multiplatform.kotlite.model.TokenType
import com.sunnychung.lib.multiplatform.kotlite.model.UnaryOpNode

class NarrativeParser(
    lexer: Lexer,
) : Parser(lexer) {

    fun narrativeScript(): ScriptNode {
        val nodes = mutableListOf<ASTNode>()
        val start = currentToken.position
        while (isSemi()) {
            semis()
        }
        while (currentToken.type != TokenType.EOF) {
            nodes += statement()
            if (currentToken.type in setOf(TokenType.Semicolon, TokenType.NewLine)) {
                semi()
            }
        }
        eat(TokenType.EOF)
        return ScriptNode(position = start, nodes = nodes)
    }

    protected override fun customStatementOrNull(): ASTNode? {
        if (currentToken.type == TokenType.Identifier) {
            return when (currentToken.value as String) {
                "choose" -> {
                    val isChooseBlock = parseAndRollback {
                        eat(TokenType.Identifier, "choose")
                        repeatedNL()
                        isCurrentToken(TokenType.Symbol, "{")
                    }
                    if (isChooseBlock) narrativeChoose() else null
                }
                "checkpoint" -> narrativeCheckpoint()
                "jump" -> narrativeJump()
                else -> null
            }
        }
        return null
    }

    private fun narrativeCheckpoint(): NarrativeCheckpointNode {
        val token = eat(TokenType.Identifier, "checkpoint")
        repeatedNL()
        val label = userDefinedIdentifier()
        return NarrativeCheckpointNode(position = token.position, label = label)
    }

    private fun narrativeJump(): NarrativeJumpNode {
        val token = eat(TokenType.Identifier, "jump")
        repeatedNL()
        val label = userDefinedIdentifier()
        return NarrativeJumpNode(position = token.position, label = label)
    }

    private fun narrativeChoose(): NarrativeChooseNode {
        val token = eat(TokenType.Identifier, "choose")
        repeatedNL()
        eat(TokenType.Symbol, "{")
        repeatedNL()
        val entries = mutableListOf<NarrativeChooseEntryNode>()
        while (!isCurrentTokenExcludingNL(TokenType.Symbol, "}")) {
            entries += narrativeChooseEntry()
            if (currentToken.type in setOf(TokenType.Semicolon, TokenType.NewLine)) {
                semis()
            } else {
                repeatedNL()
            }
        }
        repeatedNL()
        eat(TokenType.Symbol, "}")
        return NarrativeChooseNode(position = token.position, entries = entries)
    }

    private fun narrativeChooseEntry(): NarrativeChooseEntryNode {
        val parsedHead = parseChooseEntryHead(expression())
        val textNode = parsedHead.text
        repeatedNL()

        var visibleCondition: ASTNode? = parsedHead.visibleCondition
        var disableCondition: ASTNode? = parsedHead.disableCondition
        var disabledText: ASTNode? = parsedHead.disabledText

        if (isCurrentToken(TokenType.Identifier, "if")) {
            require(visibleCondition == null && disableCondition == null) {
                "Narrative choose entry already has a condition in option expression"
            }
            eat(TokenType.Identifier, "if")
            repeatedNL()
            visibleCondition = expression()
            repeatedNL()
        } else if (isCurrentToken(TokenType.Identifier, "disableIf")) {
            require(visibleCondition == null && disableCondition == null) {
                "Narrative choose entry already has a condition in option expression"
            }
            eat(TokenType.Identifier, "disableIf")
            repeatedNL()
            val parsedDisableExpr = expression()
            if (parsedDisableExpr is InfixFunctionCallNode && parsedDisableExpr.functionName == "with") {
                disableCondition = parsedDisableExpr.node1
                disabledText = parsedDisableExpr.node2
            } else {
                disableCondition = parsedDisableExpr
                if (isCurrentTokenExcludingNL(TokenType.Identifier, "with")) {
                    repeatedNL()
                    eat(TokenType.Identifier, "with")
                    repeatedNL()
                    disabledText = expression()
                }
            }
            repeatedNL()
        } else if (disableCondition != null && disabledText == null && isCurrentToken(TokenType.Identifier, "with")) {
            eat(TokenType.Identifier, "with")
            repeatedNL()
            disabledText = expression()
            repeatedNL()
        }

        eat(TokenType.Symbol, "->")
        repeatedNL()

        val action = if (isCurrentToken(TokenType.Identifier, "jump")) {
            val jump = narrativeJump()
            BlockNode(
                statements = listOf(jump),
                position = jump.position,
                type = ScopeType.If,
                format = FunctionBodyFormat.Statement,
            )
        } else {
            controlStructureBody(ScopeType.If)
        }

        return NarrativeChooseEntryNode(
            position = textNode.position,
            text = textNode,
            visibleCondition = visibleCondition,
            disableCondition = disableCondition,
            disabledText = disabledText,
            action = action,
        )
    }

    private fun parseChooseEntryHead(head: ASTNode): ParsedChooseEntryHead {
        val ifExtraction = extractChooseModifier(head, "if")
        if (ifExtraction != null) {
            return ParsedChooseEntryHead(
                text = ifExtraction.text,
                visibleCondition = ifExtraction.condition,
            )
        }

        if (head is InfixFunctionCallNode && head.functionName == "with") {
            val disableExtraction = extractChooseModifier(head.node1, "disableIf")
            if (disableExtraction != null) {
                return ParsedChooseEntryHead(
                    text = disableExtraction.text,
                    disableCondition = disableExtraction.condition,
                    disabledText = head.node2,
                )
            }
        }

        val disableExtraction = extractChooseModifier(head, "disableIf")
        if (disableExtraction != null) {
            return ParsedChooseEntryHead(
                text = disableExtraction.text,
                disableCondition = disableExtraction.condition,
            )
        }

        return ParsedChooseEntryHead(text = head)
    }

    private fun extractChooseModifier(
        node: ASTNode,
        modifierName: String,
    ): ExtractedChooseModifier? {
        return when (node) {
            is InfixFunctionCallNode -> {
                if (node.functionName == modifierName) {
                    ExtractedChooseModifier(text = node.node1, condition = node.node2)
                } else {
                    extractChooseModifier(node.node1, modifierName)?.let { extracted ->
                        extracted.copy(
                            condition = InfixFunctionCallNode(
                                position = node.position,
                                node1 = extracted.condition,
                                node2 = node.node2,
                                functionName = node.functionName,
                            )
                        )
                    }
                }
            }
            is BinaryOpNode -> {
                extractChooseModifier(node.node1, modifierName)?.let { extracted ->
                    extracted.copy(
                        condition = BinaryOpNode(
                            position = node.position,
                            node1 = extracted.condition,
                            node2 = node.node2,
                            operator = node.operator,
                        )
                    )
                }
            }
            is ElvisOpNode -> {
                extractChooseModifier(node.primaryNode, modifierName)?.let { extracted ->
                    extracted.copy(
                        condition = ElvisOpNode(
                            position = node.position,
                            primaryNode = extracted.condition,
                            fallbackNode = node.fallbackNode,
                        )
                    )
                }
            }
            is UnaryOpNode -> {
                val operand = node.node ?: return null
                extractChooseModifier(operand, modifierName)?.let { extracted ->
                    extracted.copy(
                        condition = UnaryOpNode(
                            position = node.position,
                            operator = node.operator,
                            node = extracted.condition,
                        )
                    )
                }
            }
            else -> null
        }
    }
}

private data class ParsedChooseEntryHead(
    val text: ASTNode,
    val visibleCondition: ASTNode? = null,
    val disableCondition: ASTNode? = null,
    val disabledText: ASTNode? = null,
)

private data class ExtractedChooseModifier(
    val text: ASTNode,
    val condition: ASTNode,
)
