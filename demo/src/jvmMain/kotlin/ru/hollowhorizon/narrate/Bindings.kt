package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.narrative.ImmediateNarrativeFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValue
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueRestoreContext
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.toKotlite
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class NarrativeObject(val name: String, val age: Int, val weight: Double)

fun defaultBindings(host: NarrativeHost) = NarrativeBindings {
    val narrativeObjectType = NarrativeObject::class.toKotlite("narrative_object")

    install(AllStdLibModules())
    register(NarrativeBuiltinFunctions.definitions(host))
    registerHostType(
        type = narrativeObjectType,
        snapshotClass = NarrativeObjectSnapshot::class,
        snapshotSerializer = NarrativeObjectSnapshot.serializer(),
        serialize = { value -> NarrativeObjectSnapshot(value.name, value.age, value.weight) },
        deserialize = { snapshot, context ->
            context.awaitWorldReady()
            NarrativeObject(snapshot.name, snapshot.age, snapshot.weight)
        },
    )

    register(
        ImmediateNarrativeFunctionDefinition(
            id = "NarrativeObject",
            execute = { arguments, _ ->
                require(arguments.size == 3) { "`NarrativeObject` expects (name, age, weight)" }
                NarrativeValue.HostObject(
                    typeId = narrativeObjectType.typeId,
                    value = NarrativeObject(
                        name = arguments[0].asText(),
                        age = arguments[1].asInt(),
                        weight = arguments[2].asDouble(),
                    ),
                )
            },
        )
    )
}

@Serializable
@SerialName("narrative_object")
data class NarrativeObjectSnapshot(
    val name: String,
    val age: Int,
    val weight: Double,
) : NarrativeValueSnapshot()

private fun NarrativeValue.asText(): String {
    return when (this) {
        is NarrativeValue.Text -> value
        else -> throw IllegalArgumentException("Expected text but got $this")
    }
}

private fun NarrativeValue.asInt(): Int {
    return when (this) {
        is NarrativeValue.Int32 -> value
        else -> throw IllegalArgumentException("Expected int but got $this")
    }
}

private fun NarrativeValue.asDouble(): Double {
    return when (this) {
        is NarrativeValue.Int32 -> value.toDouble()
        is NarrativeValue.Float64 -> value
        else -> throw IllegalArgumentException("Expected numeric but got $this")
    }
}

private suspend fun NarrativeValueRestoreContext.awaitWorldReady() {
    // Demo stub: keep suspend point to illustrate async deserialization hook.
}
