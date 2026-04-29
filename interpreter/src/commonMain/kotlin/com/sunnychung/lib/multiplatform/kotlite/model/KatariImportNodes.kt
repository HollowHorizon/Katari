package com.sunnychung.lib.multiplatform.kotlite.model

sealed interface KatariImportNode : ASTNode

data class KatariQualifiedImportNode(
    override val position: SourcePosition,
    val path: List<String>,
    val alias: String? = null,
    val isWildcard: Boolean = false,
) : KatariImportNode {
    override fun toMermaid(): String = ""
}

data class KatariScriptImportNode(
    override val position: SourcePosition,
    val path: String,
    val alias: String? = null,
    val isLoad: Boolean = false,
) : KatariImportNode {
    override fun toMermaid(): String = ""
}
