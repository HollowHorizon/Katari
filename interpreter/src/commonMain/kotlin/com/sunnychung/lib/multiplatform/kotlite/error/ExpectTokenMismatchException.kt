package com.sunnychung.lib.multiplatform.kotlite.error

import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.Token

open class ExpectTokenMismatchException(
    expected: String,
    position: SourcePosition?,
    token: Token? = null,
)
    : ParseException(
        position = position,
        description = buildString {
            append("Expected ")
            append(expected)
            if (token != null) {
                append(", got ")
                append(token.describeForDiagnostic())
            }
        },
    ) {
    constructor(expected: String, position: SourcePosition) : this(expected, position, null)
}

class ExpectTokenMismatchExceptionWithActual(expected: String, token: Token)
    : ExpectTokenMismatchException(expected, token.position, token)
