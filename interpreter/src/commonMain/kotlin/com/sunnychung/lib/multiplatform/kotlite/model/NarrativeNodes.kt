package com.sunnychung.lib.multiplatform.kotlite.model

data class NarrativeCheckpointNode(
    override val position: SourcePosition,
    val label: String,
) : ASTNode {
    override fun toMermaid(): String = "${generateId()}[\"Checkpoint '$label'\"]"
}

data class NarrativeJumpNode(
    override val position: SourcePosition,
    val label: String,
) : ASTNode {
    override fun toMermaid(): String = "${generateId()}[\"Jump '$label'\"]"
}

data class NarrativeChooseNode(
    override val position: SourcePosition,
    val entries: List<NarrativeChooseEntryNode>,
) : ASTNode {
    override fun toMermaid(): String {
        val self = "${generateId()}[\"Narrative Choose\"]"
        return entries.withIndex().joinToString("\n") { "$self-- \"entry[${it.index}]\" -->${it.value.toMermaid()}" }
    }
}

data class NarrativeChooseEntryNode(
    override val position: SourcePosition,
    val text: ASTNode,
    val visibleCondition: ASTNode?,
    val disableCondition: ASTNode?,
    val disabledText: ASTNode?,
    val action: BlockNode,
) : ASTNode {
    override fun toMermaid(): String {
        val self = "${generateId()}[\"Narrative Choose Entry\"]"
        return buildString {
            append("$self--text-->${text.toMermaid()}")
            visibleCondition?.let { append("\n$self--visibleIf-->${it.toMermaid()}") }
            disableCondition?.let { append("\n$self--disableIf-->${it.toMermaid()}") }
            disabledText?.let { append("\n$self--disabledText-->${it.toMermaid()}") }
            append("\n$self--action-->${action.toMermaid()}")
        }
    }
}
