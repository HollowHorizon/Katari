package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.Parser
import com.sunnychung.lib.multiplatform.kotlite.error.UnexpectedTokenException
import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.model.ASTNode
import com.sunnychung.lib.multiplatform.kotlite.model.BinaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.BlockNode
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanNode
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleNode
import com.sunnychung.lib.multiplatform.kotlite.model.ElvisOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionBodyFormat
import com.sunnychung.lib.multiplatform.kotlite.model.IntegerNode
import com.sunnychung.lib.multiplatform.kotlite.model.InfixFunctionCallNode
import com.sunnychung.lib.multiplatform.kotlite.model.KatariImportNode
import com.sunnychung.lib.multiplatform.kotlite.model.KatariQualifiedImportNode
import com.sunnychung.lib.multiplatform.kotlite.model.KatariScriptImportNode
import com.sunnychung.lib.multiplatform.kotlite.model.LabelNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCheckpointNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseEntryNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeChooseNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeAsyncNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeJumpNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeRaceEntryNode
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeRaceNode
import com.sunnychung.lib.multiplatform.kotlite.model.LongNode
import com.sunnychung.lib.multiplatform.kotlite.model.NullNode
import com.sunnychung.lib.multiplatform.kotlite.model.ScopeType
import com.sunnychung.lib.multiplatform.kotlite.model.ScriptNode
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.StringLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.StringNode
import com.sunnychung.lib.multiplatform.kotlite.model.StructArrayLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.StructEntryLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.StructLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.TokenType
import com.sunnychung.lib.multiplatform.kotlite.model.UnaryOpNode
import com.sunnychung.lib.multiplatform.kotlite.model.VariableReferenceNode
import com.sunnychung.lib.multiplatform.kotlite.model.XmlAttributeLiteralNode
import com.sunnychung.lib.multiplatform.kotlite.model.XmlNodeLiteralNode

class KatariParser(
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
                "import" -> katariImport()
                else -> null
            }
        }
        return null
    }

    override fun customPrimaryExpressionOrNull(label: LabelNode?): ASTNode? {
        if (isCurrentToken(TokenType.Operator, "<")) {
            return xmlNodeLiteral()
        }
        if (currentToken.type == TokenType.StringLiteral) {
            val token = eat(TokenType.StringLiteral)
            return StringNode(token.position, listOf(StringLiteralNode(token.position, token.value as String)))
        }
        if (currentToken.type == TokenType.Identifier) {
            return when (currentToken.value as String) {
                "async" -> narrativeAsync()
                "race" -> narrativeRace()
                "struct" -> structLiteral()
                else -> null
            }
        }
        return null
    }

    private fun xmlNodeLiteral(): XmlNodeLiteralNode {
        val start = eat(TokenType.Operator, "<")
        val name = eat(TokenType.Identifier).value as String
        val attributes = mutableListOf<XmlAttributeLiteralNode>()
        while (!isCurrentToken(TokenType.Operator, ">") && !isCurrentToken(TokenType.Operator, "/")) {
            if (currentToken.type == TokenType.NewLine) {
                repeatedNL()
                continue
            }
            val attrPosition = currentToken.position
            val attrName = eat(TokenType.Identifier).value as String
            repeatedNL()
            eat(TokenType.Symbol, "=")
            repeatedNL()
            attributes += XmlAttributeLiteralNode(
                position = attrPosition,
                name = attrName,
                value = xmlAttributeValue(),
            )
            repeatedNL()
        }
        if (isCurrentToken(TokenType.Operator, "/")) {
            eat(TokenType.Operator, "/")
            eat(TokenType.Operator, ">")
            return XmlNodeLiteralNode(start.position, name, attributes, emptyList())
        }
        eat(TokenType.Operator, ">")

        val children = mutableListOf<ASTNode>()
        while (!isXmlClosingTag(name)) {
            when {
                currentToken.type in setOf(TokenType.NewLine, TokenType.Semicolon) -> semis()
                isCurrentToken(TokenType.Operator, "<") -> children += xmlNodeLiteral()
                else -> {
                    children += statement()
                    if (currentToken.type in setOf(TokenType.NewLine, TokenType.Semicolon)) {
                        semis()
                    }
                }
            }
        }
        eatXmlClosingTag(name)
        return XmlNodeLiteralNode(start.position, name, attributes, children)
    }

    private fun xmlAttributeValue(): ASTNode {
        return when {
            isCurrentToken(TokenType.Symbol, "\"") || isCurrentToken(TokenType.Symbol, "\"\"\"") -> stringLiteral()
            currentToken.type == TokenType.StringLiteral -> {
                val token = eat(TokenType.StringLiteral)
                StringNode(token.position, listOf(StringLiteralNode(token.position, token.value as String)))
            }
            isCurrentToken(TokenType.Symbol, "{") -> bracedXmlExpression()
            else -> xmlSimpleAttributeExpression()
        }
    }

    private fun bracedXmlExpression(): ASTNode {
        eat(TokenType.Symbol, "{")
        repeatedNL()
        val value = expression()
        repeatedNL()
        eat(TokenType.Symbol, "}")
        return value
    }

    private fun xmlSimpleAttributeExpression(): ASTNode {
        return when (currentToken.type) {
            TokenType.Identifier -> {
                val token = eat(TokenType.Identifier)
                when (token.value) {
                    "true" -> BooleanNode(token.position, true)
                    "false" -> BooleanNode(token.position, false)
                    "null" -> NullNode
                    else -> VariableReferenceNode(token.position, token.value as String)
                }
            }
            TokenType.Integer -> {
                val token = eat(TokenType.Integer)
                IntegerNode(token.position, token.value as Int)
            }
            TokenType.Long -> {
                val token = eat(TokenType.Long)
                LongNode(token.position, token.value as Long)
            }
            TokenType.Double -> {
                val token = eat(TokenType.Double)
                DoubleNode(token.position, token.value as Double)
            }
            else -> throw UnexpectedTokenException(currentToken)
        }
    }

    private fun isXmlClosingTag(name: String): Boolean {
        return parseAndRollback {
            if (!isCurrentToken(TokenType.Operator, "<")) return@parseAndRollback false
            eat(TokenType.Operator, "<")
            if (!isCurrentToken(TokenType.Operator, "/")) return@parseAndRollback false
            eat(TokenType.Operator, "/")
            if (!isCurrentToken(TokenType.Identifier, name)) return@parseAndRollback false
            eat(TokenType.Identifier, name)
            isCurrentToken(TokenType.Operator, ">")
        }
    }

    private fun eatXmlClosingTag(name: String) {
        eat(TokenType.Operator, "<")
        eat(TokenType.Operator, "/")
        eat(TokenType.Identifier, name)
        eat(TokenType.Operator, ">")
    }

    private fun structLiteral(): StructLiteralNode {
        val token = eat(TokenType.Identifier, "struct")
        repeatedNL()
        return structObjectLiteral(token.position)
    }

    private fun structObjectLiteral(position: SourcePosition = currentToken.position): StructLiteralNode {
        eat(TokenType.Symbol, "{")
        repeatedNL()
        val entries = mutableListOf<StructEntryLiteralNode>()
        var hasComma = false
        while (!isCurrentTokenExcludingNL(TokenType.Symbol, "}")) {
            if (entries.isNotEmpty() && !hasComma) {
                throw UnexpectedTokenException(currentToken)
            }
            repeatedNL()
            val keyPosition = currentToken.position
            val key = structKey()
            repeatedNL()
            eat(TokenType.Symbol, ":")
            repeatedNL()
            entries += StructEntryLiteralNode(keyPosition, key, structValue())
            repeatedNL()
            hasComma = false
            if (isCurrentToken(TokenType.Symbol, ",")) {
                eat(TokenType.Symbol, ",")
                repeatedNL()
                hasComma = true
            }
        }
        repeatedNL()
        eat(TokenType.Symbol, "}")
        return StructLiteralNode(position, entries)
    }

    private fun structKey(): String {
        return when (currentToken.type) {
            TokenType.Identifier -> eat(TokenType.Identifier).value as String
            TokenType.StringLiteral -> eat(TokenType.StringLiteral).value as String
            TokenType.Symbol -> {
                val string = stringLiteral() as? StringNode ?: throw UnexpectedTokenException(currentToken)
                string.nodes.joinToString("") { (it as StringLiteralNode).content }
            }
            else -> throw UnexpectedTokenException(currentToken)
        }
    }

    private fun structValue(): ASTNode {
        return when {
            isCurrentToken(TokenType.Symbol, "{") -> structObjectLiteral()
            isCurrentToken(TokenType.Operator, "[") -> structArrayLiteral()
            else -> expression()
        }
    }

    private fun structArrayLiteral(): StructArrayLiteralNode {
        val token = eat(TokenType.Operator, "[")
        repeatedNL()
        val elements = mutableListOf<ASTNode>()
        var hasComma = false
        while (!isCurrentTokenExcludingNL(TokenType.Operator, "]")) {
            if (elements.isNotEmpty() && !hasComma) {
                throw UnexpectedTokenException(currentToken)
            }
            repeatedNL()
            elements += structValue()
            repeatedNL()
            hasComma = false
            if (isCurrentToken(TokenType.Symbol, ",")) {
                eat(TokenType.Symbol, ",")
                repeatedNL()
                hasComma = true
            }
        }
        repeatedNL()
        eat(TokenType.Operator, "]")
        return StructArrayLiteralNode(token.position, elements)
    }

    private fun narrativeAsync(): NarrativeAsyncNode {
        val token = eat(TokenType.Identifier, "async")
        repeatedNL()
        val body = block(ScopeType.Function)
        return NarrativeAsyncNode(position = token.position, body = body)
    }

    private fun narrativeRace(): NarrativeRaceNode {
        val token = eat(TokenType.Identifier, "race")
        repeatedNL()
        eat(TokenType.Symbol, "{")
        repeatedNL()
        val entries = mutableListOf<NarrativeRaceEntryNode>()
        while (!isCurrentTokenExcludingNL(TokenType.Symbol, "}")) {
            val action = expression()
            repeatedNL()
            eat(TokenType.Symbol, "->")
            repeatedNL()
            val result = expression()
            entries += NarrativeRaceEntryNode(position = action.position, action = action, result = result)
            if (currentToken.type in setOf(TokenType.Semicolon, TokenType.NewLine)) {
                semis()
            } else {
                repeatedNL()
            }
        }
        eat(TokenType.Symbol, "}")
        return NarrativeRaceNode(position = token.position, entries = entries)
    }

    private fun katariImport(): KatariImportNode {
        val token = eat(TokenType.Identifier, "import")
        repeatedNL()
        if (isCurrentToken(TokenType.Identifier, "katari")) {
            eat(TokenType.Identifier, "katari")
            repeatedNL()
            val path = stringImportPath()
            val alias = optionalAlias()
            return KatariScriptImportNode(position = token.position, path = path, alias = alias)
        }
        val path = mutableListOf(eat(TokenType.Identifier).value as String)
        var isWildcard = false
        while (isCurrentTokenExcludingNL(TokenType.Operator, ".")) {
            repeatedNL()
            eat(TokenType.Operator, ".")
            repeatedNL()
            if (isCurrentToken(TokenType.Operator, "*")) {
                eat(TokenType.Operator, "*")
                isWildcard = true
                break
            }
            path += eat(TokenType.Identifier).value as String
        }
        val alias = optionalAlias()
        return KatariQualifiedImportNode(position = token.position, path = path, alias = alias, isWildcard = isWildcard)
    }

    private fun optionalAlias(): String? {
        repeatedNL()
        return if (isCurrentToken(TokenType.Identifier, "as")) {
            eat(TokenType.Identifier, "as")
            repeatedNL()
            eat(TokenType.Identifier).value as String
        } else {
            null
        }
    }

    private fun stringImportPath(): String {
        val value = when {
            isCurrentToken(TokenType.Symbol, "\"") -> stringLiteral()
            isCurrentToken(TokenType.Symbol, "\"\"\"") -> stringLiteral()
            currentToken.type == TokenType.StringLiteral -> {
                val token = eat(TokenType.StringLiteral)
                StringNode(token.position, listOf(StringLiteralNode(token.position, token.value as String)))
            }
            else -> throw UnexpectedTokenException(currentToken)
        }
        val stringNode = value as? StringNode
            ?: throw UnexpectedTokenException(currentToken)
        return stringNode.nodes.joinToString("") { node ->
            (node as? StringLiteralNode)?.content
                ?: throw UnexpectedTokenException(currentToken)
        }
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
