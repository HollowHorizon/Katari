package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.lexer.Lexer
import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter

fun KatariNarrativeProgram(
    filename: String,
    code: String,
    sourceProvider: KatariSourceProvider = EmptyKatariSourceProvider,
): KatariProgram {
    val ast = KatariParser(Lexer(filename = filename, code = code, isParseSingleQuotedString = true)).narrativeScript()
    val imports = resolveKatariImports(filename, ast, sourceProvider)
    return KatariCompiler(
        nameAliases = imports.nameAliases,
        scriptNamespaces = imports.scriptNamespaces,
    ).compile(imports.script)
}

fun KatariNarrativeProgram(
    filename: String,
    code: String,
    bindings: KatariBindings,
    sourceProvider: KatariSourceProvider = EmptyKatariSourceProvider,
): KatariProgram {
    val ast = KatariParser(Lexer(filename = filename, code = code, isParseSingleQuotedString = true)).narrativeScript()
    val imports = resolveKatariImports(filename, ast, sourceProvider)
    val interpreter = KotliteInterpreter(
        filename = "<NarrativeInline>",
        code = "",
        executionEnvironment = bindings.executionEnvironment,
    )
    val declarations = bindings.executionEnvironment.getBuiltinFunctions(interpreter.symbolTable())
    return KatariCompiler(
        inlineEnvironmentFunctions = declarations,
        importedEnumDefinitions = bindings.enumDefinitions,
        nameAliases = bindings.importAliases + imports.nameAliases,
        scriptNamespaces = imports.scriptNamespaces,
    ).compile(imports.script)
}
