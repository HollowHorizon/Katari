package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.Parser
import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer

fun KotliteNarrativeProgram(filename: String, code: String): NarrativeProgram {
    val ast = Parser(Lexer(filename = filename, code = code)).script()
    return NarrativeCompiler().compile(ast)
}
