package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter

fun KotliteNarrativeProgram(filename: String, code: String): NarrativeProgram {
    val ast = NarrativeParser(Lexer(filename = filename, code = code)).narrativeScript()
    return NarrativeCompiler().compile(ast)
}

fun KotliteNarrativeProgram(
    filename: String,
    code: String,
    bindings: NarrativeBindings,
): NarrativeProgram {
    val ast = NarrativeParser(Lexer(filename = filename, code = code)).narrativeScript()
    val interpreter = KotliteInterpreter(
        filename = "<NarrativeInline>",
        code = "",
        executionEnvironment = bindings.executionEnvironment,
    )
    val declarations = bindings.executionEnvironment.getBuiltinFunctions(interpreter.symbolTable())
    return NarrativeCompiler(inlineEnvironmentFunctions = declarations).compile(ast)
}
