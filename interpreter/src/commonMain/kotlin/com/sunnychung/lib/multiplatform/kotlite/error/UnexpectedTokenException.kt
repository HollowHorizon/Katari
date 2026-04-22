package com.sunnychung.lib.multiplatform.kotlite.error

import com.sunnychung.lib.multiplatform.kotlite.model.Token
import com.sunnychung.lib.multiplatform.kotlite.model.TokenType

class UnexpectedTokenException(val token: Token) : ParseException(
    position = token.position,
    description = "Unexpected ${token.describeForDiagnostic()}",
)

internal fun Token.describeForDiagnostic(): String {
    return when (type) {
        TokenType.EOF -> "end of file"
        TokenType.NewLine -> "new line"
        else -> "${type.name.lowercase()} `${value.describeTokenValue()}`"
    }
}

private fun Any.describeTokenValue(): String {
    return when (this) {
        '\u0000' -> "<EOF>"
        "\n" -> "\\n"
        "\r" -> "\\r"
        "\t" -> "\\t"
        else -> toString()
    }
}
