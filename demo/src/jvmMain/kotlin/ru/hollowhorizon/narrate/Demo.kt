package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.narrative.KotliteNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionRegistry
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeInstance
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeStateSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeStateSnapshotCodec
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.io.File

private const val SCRIPT = """
    "Темная комната. Слышны шаги."
    npc.say("Наконец-то ты пришёл.")
    
    var name = readLine()
    var mood = "neutral"
    
    if (name == "Игорь") {
        npc.say("Привет, Игорь!")
        mood = "friendly"
    } else {
        npc.say("Привет, незнакомец.")
        mood = "suspicious"
    }
    
    "НПС внимательно смотрит на тебя."
    
    val action = choose(
        "Спросить кто он такой",
        "Промолчать",
        "Уйти"
    )
    
    if (action == "Спросить кто он такой") {
        npc.say("Я тот, кто наблюдал за тобой.")
        
        if (mood == "friendly") {
            npc.say("И, возможно, даже помогал тебе.")
        } else {
            npc.say("Но пока не уверен, можно ли тебе доверять.")
        }
    } else if (action == "Промолчать") {
        "Ты ничего не отвечаешь."
        npc.say("Молчание тоже бывает ответом.")
    } else {
        "Ты разворачиваешься к выходу."
        npc.say("Мы ещё встретимся.")
    }
    
    "Сцена завершена."
"""

fun main() = runBlocking {
    val host = SwingNarrativeHost()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val program = KotliteNarrativeProgram(
        filename = "<Narrative>",
        code = SCRIPT.trimIndent(),
    )
    val functionRegistry = NarrativeBuiltinFunctions.registry(host)
    val snapshotCodec = NarrativeStateSnapshotCodec(functionRegistry = functionRegistry)
    val json = Json {
        serializersModule = snapshotCodec.serializersModule()
        classDiscriminator = "kind"
        prettyPrint = true
    }
    val cbor = Cbor {
        serializersModule = snapshotCodec.serializersModule()
    }

    var currentInstance = createInstance(
        program = program,
        initialState = initialState(program),
        functionRegistry = functionRegistry,
        snapshotCodec = snapshotCodec,
        scope = scope,
    )

    fun replaceInstance(state: NarrativeState, clearTranscript: Boolean) {
        currentInstance.cancel()
        if (clearTranscript) {
            host.clearTranscript()
        }
        currentInstance = createInstance(
            program = program,
            initialState = state,
            functionRegistry = functionRegistry,
            snapshotCodec = snapshotCodec,
            scope = scope,
        )
        host.setStatus("Running")
        currentInstance.start()
    }

    host.bindActions(
        onSaveJson = { file ->
            scope.launch {
                saveJson(currentInstance, json, file, host)
            }
        },
        onLoadJson = { file ->
            scope.launch {
                loadJson(file, snapshotCodec, json, host) { state ->
                    replaceInstance(state, clearTranscript = true)
                }
            }
        },
        onSaveCbor = { file ->
            scope.launch {
                saveCbor(currentInstance, cbor, file, host)
            }
        },
        onLoadCbor = { file ->
            scope.launch {
                loadCbor(file, snapshotCodec, cbor, host) { state ->
                    replaceInstance(state, clearTranscript = true)
                }
            }
        },
        onImportHistory = { file ->
            scope.launch {
                importHistory(file, host)
            }
        },
    )

    host.setStatus("Running")
    currentInstance.start()
}

private fun createInstance(
    program: NarrativeProgram,
    initialState: NarrativeState,
    functionRegistry: NarrativeFunctionRegistry,
    snapshotCodec: NarrativeStateSnapshotCodec,
    scope: CoroutineScope,
): NarrativeInstance {
    return NarrativeInstance(
        program = program,
        initialState = initialState,
        functionRegistry = functionRegistry,
        snapshotCodec = snapshotCodec,
        coroutineScope = scope,
    )
}

private fun initialState(program: NarrativeProgram): NarrativeState {
    return NarrativeState(
        programVersion = program.version,
        tasks = listOf(NarrativeTaskState(id = program.entryTaskId)),
        globals = mapOf("npc" to NarrativeValue.Entity("npc_1")),
    )
}

private suspend fun saveJson(
    instance: NarrativeInstance,
    json: Json,
    file: File,
    host: SwingNarrativeHost,
) {
    saveSnapshot(instance, file, host, "Saved JSON: ${file.name}") { snapshot ->
        file.writeText(json.encodeToString(NarrativeStateSnapshot.serializer(), snapshot))
    }
}

private suspend fun saveCbor(
    instance: NarrativeInstance,
    cbor: Cbor,
    file: File,
    host: SwingNarrativeHost,
) {
    saveSnapshot(instance, file, host, "Saved CBOR: ${file.name}") { snapshot ->
        file.writeBytes(cbor.encodeToByteArray(NarrativeStateSnapshot.serializer(), snapshot))
    }
}

private suspend fun saveSnapshot(
    instance: NarrativeInstance,
    file: File,
    host: SwingNarrativeHost,
    successMessage: String,
    writer: (NarrativeStateSnapshot) -> Unit,
) {
    runCatching {
        host.setStatus("Saving ${file.name}...")
        writer(instance.serializeState())
        host.setStatus(successMessage)
    }.onFailure { error ->
        host.setStatus("Save failed")
        host.showError("Failed to save snapshot", error)
    }
}

private suspend fun loadJson(
    file: File,
    snapshotCodec: NarrativeStateSnapshotCodec,
    json: Json,
    host: SwingNarrativeHost,
    onLoaded: (NarrativeState) -> Unit,
) {
    loadSnapshot(file, snapshotCodec, host, "Loaded JSON: ${file.name}", onLoaded) {
        json.decodeFromString(NarrativeStateSnapshot.serializer(), file.readText())
    }
}

private suspend fun loadCbor(
    file: File,
    snapshotCodec: NarrativeStateSnapshotCodec,
    cbor: Cbor,
    host: SwingNarrativeHost,
    onLoaded: (NarrativeState) -> Unit,
) {
    loadSnapshot(file, snapshotCodec, host, "Loaded CBOR: ${file.name}", onLoaded) {
        cbor.decodeFromByteArray(NarrativeStateSnapshot.serializer(), file.readBytes())
    }
}

private suspend fun loadSnapshot(
    file: File,
    snapshotCodec: NarrativeStateSnapshotCodec,
    host: SwingNarrativeHost,
    successMessage: String,
    onLoaded: (NarrativeState) -> Unit,
    reader: () -> NarrativeStateSnapshot,
) {
    runCatching {
        host.setStatus("Loading ${file.name}...")
        val snapshot = reader()
        val state = snapshotCodec.restore(snapshot)
        onLoaded(state)
        host.setStatus(successMessage)
    }.onFailure { error ->
        host.setStatus("Load failed")
        host.showError("Failed to load snapshot", error)
    }
}

private fun importHistory(file: File, host: SwingNarrativeHost) {
    runCatching {
        host.setStatus("Importing history...")
        host.replaceTranscript(file.readText())
        host.setStatus("Imported history: ${file.name}")
    }.onFailure { error ->
        host.setStatus("History import failed")
        host.showError("Failed to import history", error)
    }
}
