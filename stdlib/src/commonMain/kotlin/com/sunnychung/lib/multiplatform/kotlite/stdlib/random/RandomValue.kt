package com.sunnychung.lib.multiplatform.kotlite.stdlib.random

import com.sunnychung.lib.multiplatform.kotlite.model.DelegatedValue
import com.sunnychung.lib.multiplatform.kotlite.model.ProvidedClassDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.SymbolTable
import kotlin.random.Random

fun KRandomValue(value: Random, symbolTable: SymbolTable) =
    DelegatedValue(value, KRandomClass.clazz, emptyList(), symbolTable)

object KRandomClass {
    val clazz = ProvidedClassDefinition(
        fullQualifiedName = "KRandom",
        typeParameters = emptyList(),
        isInstanceCreationAllowed = false,
        primaryConstructorParameters = emptyList(),
        constructInstance = { _, _, _ -> throw UnsupportedOperationException() },
        position = SourcePosition("Random", 1, 1),
    )
}
