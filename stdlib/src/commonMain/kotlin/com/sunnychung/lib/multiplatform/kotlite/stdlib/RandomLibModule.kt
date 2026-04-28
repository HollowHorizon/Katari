package com.sunnychung.lib.multiplatform.kotlite.stdlib

import com.sunnychung.lib.multiplatform.kotlite.model.GlobalProperty
import com.sunnychung.lib.multiplatform.kotlite.model.ProvidedClassDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.stdlib.random.KRandomClass
import com.sunnychung.lib.multiplatform.kotlite.stdlib.random.KRandomValue
import kotlin.random.Random

class RandomLibModule : AbstractRandomLibModule() {
    override val classes: List<ProvidedClassDefinition> = super.classes + listOf(
        KRandomClass.clazz,
    )

    override val globalProperties: List<GlobalProperty> = super.globalProperties + listOf(
        GlobalProperty(
            position = SourcePosition("Random", 1, 1),
            declaredName = "Random",
            type = "KRandom",
            isMutable = false,
            getter = { interpreter -> KRandomValue(Random.Default, interpreter.symbolTable()) },
        ),
    )
}
