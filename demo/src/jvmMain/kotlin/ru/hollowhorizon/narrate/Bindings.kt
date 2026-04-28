package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.katari.*
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class NarrativeObject(val name: String, val age: Int, val weight: Double)

fun defaultBindings(host: NarrativeHost) = NarrativeBindings {
    val narrativeObjectType = NarrativeObject::class.toKatari("narrative_object")

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
        ImmediateKatariFunctionDefinition(
            id = "NarrativeObject",
            execute = { arguments, _ ->
                require(arguments.size == 3) { "`NarrativeObject` expects (name, age, weight)" }
                KatariValue.HostObject(
                    typeId = narrativeObjectType.typeId,
                    value = NarrativeObject(
                        name = arguments[0].asText(),
                        age = arguments[1].asInt(),
                        weight = arguments[2].asDouble(),
                    ),
                )
            },
            signature = KatariCallableSignature(
                valueParameters = listOf(
                    KatariTypes.Text.asValueParameter("name"),
                    KatariTypes.Int.asValueParameter("age"),
                    KatariTypes.Int.asValueParameter("weight")
                ),
                returnType = KatariTypes.host(narrativeObjectType)
            ),
        )
    )
}

@Serializable
@SerialName("narrative_object")
data class NarrativeObjectSnapshot(
    val name: String,
    val age: Int,
    val weight: Double,
) : ValueSnapshot()

private fun KatariValue.asText(): String {
    return when (this) {
        is KatariValue.Text -> value
        else -> throw IllegalArgumentException("Expected text but got $this")
    }
}

private fun KatariValue.asInt(): Int {
    return when (this) {
        is KatariValue.Int32 -> value
        else -> throw IllegalArgumentException("Expected int but got $this")
    }
}

private fun KatariValue.asDouble(): Double {
    return when (this) {
        is KatariValue.Int32 -> value.toDouble()
        is KatariValue.Float64 -> value
        else -> throw IllegalArgumentException("Expected numeric but got $this")
    }
}

private suspend fun ValueRestoreContext.awaitWorldReady() {
    // Demo stub: keep suspend point to illustrate async deserialization hook.
}
