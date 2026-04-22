package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter

fun KatariNarrativeProgram(filename: String, code: String): KatariProgram {
    val ast = KatariParser(Lexer(filename = filename, code = code)).narrativeScript()
    return KatariCompiler().compile(ast)
}

fun KatariNarrativeProgram(
    filename: String,
    code: String,
    bindings: KatariBindings,
): KatariProgram {
    val ast = KatariParser(Lexer(filename = filename, code = code)).narrativeScript()
    val interpreter = KotliteInterpreter(
        filename = "<NarrativeInline>",
        code = "",
        executionEnvironment = bindings.executionEnvironment,
    )
    val declarations = bindings.executionEnvironment.getBuiltinFunctions(interpreter.symbolTable())
    return KatariCompiler(inlineEnvironmentFunctions = declarations).compile(ast)
}
