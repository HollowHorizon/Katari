package com.sunnychung.lib.multiplatform.kotlite.error

import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition

open class ParseException(
    val position: SourcePosition?,
    val description: String,
    cause: Throwable? = null,
) : Exception(buildDiagnosticMessage(position, "Parse error", description), cause) {
    constructor(message: String) : this(null, message)
}

internal fun buildDiagnosticMessage(position: SourcePosition?, category: String, description: String): String {
    return listOfNotNull(
        position?.toString(),
        "$category: $description",
    ).joinToString(" ")
}
