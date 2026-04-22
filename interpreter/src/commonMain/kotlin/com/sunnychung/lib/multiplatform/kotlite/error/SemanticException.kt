package com.sunnychung.lib.multiplatform.kotlite.error

import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition

open class SemanticException(
    val position: SourcePosition,
    val description: String,
    cause: Throwable? = null,
) : Exception(buildDiagnosticMessage(position, "Semantic error", description), cause)
