package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariStateSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.StateSnapshotCodec
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.io.File


fun main() = runBlocking {
    val host = SwingNarrativeHost()
    val bindings = defaultBindings(host)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val program = KatariNarrativeProgram(
        filename = "<Narrative>",
        code = SwingNarrativeHost::class.java.getResource("/script.ktlite")!!.readText(),
        bindings = bindings,
    )
    val snapshotCodec = bindings.snapshotCodec
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
        bindings = bindings,
        scope = scope,
    )
    attachFailureMonitor(host, currentInstance, scope) { monitored ->
        currentInstance === monitored
    }

    fun replaceInstance(state: KatariState, clearTranscript: Boolean) {
        currentInstance.cancel()
        if (clearTranscript) {
            host.clearTranscript()
        }
        currentInstance = createInstance(
            program = program,
            initialState = state,
            bindings = bindings,
            scope = scope,
        )
        attachFailureMonitor(host, currentInstance, scope) { monitored ->
            currentInstance === monitored
        }
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

private fun attachFailureMonitor(
    host: SwingNarrativeHost,
    instance: KatariInstance,
    scope: CoroutineScope,
    isCurrentInstance: (KatariInstance) -> Boolean,
) {
    scope.launch {
        instance.join()
        if (!isCurrentInstance(instance)) {
            return@launch
        }
        val failedMessage = instance.currentState().tasks
            .mapNotNull { (it.status as? TaskStatus.Failed)?.message }
            .firstOrNull()
        if (failedMessage != null) {
            host.setStatus("Failed")
            host.showError("Narrative execution failed", IllegalStateException(failedMessage))
        } else {
            host.setStatus("Completed")
        }
    }
}

private fun createInstance(
    program: KatariProgram,
    initialState: KatariState,
    bindings: KatariBindings,
    scope: CoroutineScope,
): KatariInstance {
    return KatariInstance(
        program = program,
        initialState = initialState.copy(globals = bindings.globals),
        executionEnvironment = bindings.executionEnvironment,
        snapshotCodec = bindings.snapshotCodec,
        coroutineScope = scope,
    )
}

private fun initialState(program: KatariProgram): KatariState {
    return KatariState(
        programVersion = program.version,
        tasks = listOf(TaskState(id = program.entryTaskId)),
        globals = emptyMap(),
    )
}

private suspend fun saveJson(
    instance: KatariInstance,
    json: Json,
    file: File,
    host: SwingNarrativeHost,
) {
    saveSnapshot(instance, file, host, "Saved JSON: ${file.name}") { snapshot ->
        file.writeText(json.encodeToString(KatariStateSnapshot.serializer(), snapshot))
    }
}

private suspend fun saveCbor(
    instance: KatariInstance,
    cbor: Cbor,
    file: File,
    host: SwingNarrativeHost,
) {
    saveSnapshot(instance, file, host, "Saved CBOR: ${file.name}") { snapshot ->
        file.writeBytes(cbor.encodeToByteArray(KatariStateSnapshot.serializer(), snapshot))
    }
}

private suspend fun saveSnapshot(
    instance: KatariInstance,
    file: File,
    host: SwingNarrativeHost,
    successMessage: String,
    writer: (KatariStateSnapshot) -> Unit,
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
    snapshotCodec: StateSnapshotCodec,
    json: Json,
    host: SwingNarrativeHost,
    onLoaded: (KatariState) -> Unit,
) {
    loadSnapshot(file, snapshotCodec, host, "Loaded JSON: ${file.name}", onLoaded) {
        json.decodeFromString(KatariStateSnapshot.serializer(), file.readText())
    }
}

private suspend fun loadCbor(
    file: File,
    snapshotCodec: StateSnapshotCodec,
    cbor: Cbor,
    host: SwingNarrativeHost,
    onLoaded: (KatariState) -> Unit,
) {
    loadSnapshot(file, snapshotCodec, host, "Loaded CBOR: ${file.name}", onLoaded) {
        cbor.decodeFromByteArray(KatariStateSnapshot.serializer(), file.readBytes())
    }
}

private suspend fun loadSnapshot(
    file: File,
    snapshotCodec: StateSnapshotCodec,
    host: SwingNarrativeHost,
    successMessage: String,
    onLoaded: (KatariState) -> Unit,
    reader: () -> KatariStateSnapshot,
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
