package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition

data class KatariPreprocessorInstruction(
    val position: SourcePosition,
    val name: String,
    val arguments: List<String>,
    val rawArguments: String,
    val rawText: String,
)

data class KatariPreprocessedSource(
    val code: String,
    val instructions: List<KatariPreprocessorInstruction>,
)

fun preprocessKatariSource(
    filename: String,
    code: String,
): KatariPreprocessedSource {
    val instructions = mutableListOf<KatariPreprocessorInstruction>()
    val output = code.split('\n').mapIndexed { index, line ->
        val leadingWhitespace = line.takeWhile { it == ' ' || it == '\t' }
        val trimmed = line.drop(leadingWhitespace.length)
        if (!trimmed.startsWith("#")) {
            line
        } else {
            val rawBody = trimmed.drop(1)
            val body = stripLineComment(rawBody).trim()
            if (body.isNotEmpty()) {
                val name = body.takeWhile { !it.isWhitespace() }
                val rawArguments = body.drop(name.length).trim()
                instructions += KatariPreprocessorInstruction(
                    position = SourcePosition(
                        filename = filename,
                        lineNum = index + 1,
                        col = leadingWhitespace.length + 1,
                        index = code.lineStartIndex(index),
                    ),
                    name = name,
                    arguments = splitPreprocessorArguments(rawArguments),
                    rawArguments = rawArguments,
                    rawText = line,
                )
            }
            ""
        }
    }.joinToString("\n")
    return KatariPreprocessedSource(code = output, instructions = instructions)
}

private fun stripLineComment(text: String): String {
    var quote: Char? = null
    var isEscaped = false
    text.forEachIndexed { index, char ->
        if (isEscaped) {
            isEscaped = false
            return@forEachIndexed
        }
        if (char == '\\') {
            isEscaped = true
            return@forEachIndexed
        }
        if (quote != null) {
            if (char == quote) {
                quote = null
            }
            return@forEachIndexed
        }
        if (char == '"' || char == '\'') {
            quote = char
            return@forEachIndexed
        }
        if (char == '/' && text.getOrNull(index + 1) == '/') {
            return text.take(index)
        }
    }
    return text
}

private fun splitPreprocessorArguments(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var isEscaped = false
    text.forEach { char ->
        if (isEscaped) {
            current.append(char)
            isEscaped = false
            return@forEach
        }
        if (char == '\\') {
            isEscaped = true
            return@forEach
        }
        if (quote != null) {
            if (char == quote) {
                quote = null
            } else {
                current.append(char)
            }
            return@forEach
        }
        when {
            char == '"' || char == '\'' -> quote = char
            char.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }
            else -> current.append(char)
        }
    }
    if (current.isNotEmpty()) {
        result += current.toString()
    }
    return result
}

private fun String.lineStartIndex(lineIndex: Int): Int {
    var currentLine = 0
    forEachIndexed { index, char ->
        if (currentLine == lineIndex) return index
        if (char == '\n') ++currentLine
    }
    return length
}
