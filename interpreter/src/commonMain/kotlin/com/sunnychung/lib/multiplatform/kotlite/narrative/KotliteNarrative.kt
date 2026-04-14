package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer

fun KotliteNarrativeProgram(filename: String, code: String): NarrativeProgram {
    val ast = NarrativeParser(Lexer(filename = filename, code = code)).narrativeScript()
    return NarrativeCompiler().compile(ast)
}
