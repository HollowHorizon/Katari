package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.RuntimeListValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NarrativeInlineStdlibTest {

    @Test
    fun inlineRepeatResumesFromSnapshotWithoutRestartingLoop() = runTest {
        assertNarrativeResumesFromSnapshot(
            code = """
                repeat(3) { index ->
                    "repeat:${'$'}index"
                }
            """.trimIndent(),
            expectedEvents = listOf("repeat:0", "repeat:1", "repeat:2"),
        )
    }

    @Test
    fun inlineForEachResumesFromSnapshotWithoutRestartingIterator() = runTest {
        assertNarrativeResumesFromSnapshot(
            code = """
                val values = listOf(1, 2, 3)
                values.forEach { value ->
                    "each:${'$'}value"
                }
            """.trimIndent(),
            expectedEvents = listOf("each:1", "each:2", "each:3"),
        )
    }

    @Test
    fun inlineForEachSupportsImplicitItLambdaParameter() = runTest {
        assertNarrativeResumesFromSnapshot(
            code = """
                val values = listOf(1, 2, 3)
                values.forEach {
                    "each:${'$'}it"
                }
            """.trimIndent(),
            expectedEvents = listOf("each:1", "each:2", "each:3"),
        )
    }

    @Test
    fun inlineMapResumesFromSnapshotAndKeepsCollectedResult() = runTest {
        assertNarrativeResumesFromSnapshot(
            code = """
                val values = listOf(1, 2, 3)
                val mapped = values.map { value ->
                    "map:${'$'}value"
                    value * 10
                }
                "${'$'}{mapped[2]}"
            """.trimIndent(),
            expectedEvents = listOf("map:1", "map:2", "map:3", "30"),
        )
    }

    @Test
    fun forLoopsOverCollectionsResumeFromSnapshotWithoutRestartingIteration() = runTest {
        assertNarrativeResumesFromSnapshot(
            code = """
                for (entry in mapOf("a" to 1, "b" to 2, "c" to 3)) {
                    "${'$'}{entry.key}:${'$'}{entry.value}"
                }
            """.trimIndent(),
            expectedEvents = listOf("a:1", "b:2", "c:3"),
        )
    }

    @Test
    fun snapshotPreservesSharedMutableReceiverAcrossRestore() = runTest {
        val code = """
            fun MutableList<Int>.mutateAndPause() {
                "pause"
                this[0] = 9
            }

            val data = mutableListOf(1, 2, 3)
            data.mutateAndPause()
            "${'$'}{data[0]}"
        """.trimIndent()
        val initialEvents = mutableListOf<String>()
        val initialBindings = stdlibBindings(
            host = object : NarrativeHost {
                override fun narrate(text: String, resume: () -> Unit) {
                    initialEvents += text
                }
                override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
                override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            }
        )
        val initialInstance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = code,
                bindings = initialBindings,
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = initialBindings.globals,
            ),
            executionEnvironment = initialBindings.executionEnvironment,
            snapshotCodec = initialBindings.snapshotCodec,
            coroutineScope = this,
        )

        initialInstance.start()
        advanceUntilIdle()

        assertEquals(listOf("pause"), initialEvents)

        val snapshot = initialInstance.serializeState()
        val task = snapshot.tasks.single()
        val rootFrame = task.callFrames.first { it.functionId == "__main__" }
        val extensionFrame = task.callFrames.first { it.functionId == "mutateAndPause" }

        assertEquals(rootFrame.variableRefs.getValue("data"), extensionFrame.variableRefs.getValue("this"))
        assertEquals(1, snapshot.values.values.filterIsInstance<RuntimeListValueSnapshot>().size)

        initialInstance.cancel()

        val resumedEvents = mutableListOf<String>()
        val resumedBindings = stdlibBindings(
            host = object : NarrativeHost {
                override fun narrate(text: String, resume: () -> Unit) {
                    resumedEvents += text
                    resume()
                }
                override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
                override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            }
        )
        val resumedInstance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = code,
                bindings = resumedBindings,
            ),
            initialState = resumedBindings.snapshotCodec.restore(snapshot),
            executionEnvironment = resumedBindings.executionEnvironment,
            snapshotCodec = resumedBindings.snapshotCodec,
            coroutineScope = this,
        )

        resumedInstance.start()
        advanceUntilIdle()
        resumedInstance.join()

        assertEquals(listOf("pause", "9"), resumedEvents)
        assertEquals(TaskStatus.Completed, resumedInstance.currentState().tasks.single().status)
    }

    private suspend fun TestScope.assertNarrativeResumesFromSnapshot(code: String, expectedEvents: List<String>) {
        val initialEvents = mutableListOf<String>()
        val initialBindings = stdlibBindings(
            host = object : NarrativeHost {
                override fun narrate(text: String, resume: () -> Unit) {
                    initialEvents += text
                }
                override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
                override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            }
        )
        val initialInstance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = code,
                bindings = initialBindings,
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = initialBindings.globals,
            ),
            executionEnvironment = initialBindings.executionEnvironment,
            snapshotCodec = initialBindings.snapshotCodec,
            coroutineScope = this,
        )

        initialInstance.start()
        advanceUntilIdle()

        assertEquals(listOf(expectedEvents.first()), initialEvents)

        val snapshot = initialInstance.serializeState()
        initialInstance.cancel()

        val resumedEvents = mutableListOf<String>()
        val resumedBindings = stdlibBindings(
            host = object : NarrativeHost {
                override fun narrate(text: String, resume: () -> Unit) {
                    resumedEvents += text
                    resume()
                }
                override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
                override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            }
        )
        val resumedInstance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = code,
                bindings = resumedBindings,
            ),
            initialState = resumedBindings.snapshotCodec.restore(snapshot),
            executionEnvironment = resumedBindings.executionEnvironment,
            snapshotCodec = resumedBindings.snapshotCodec,
            coroutineScope = this,
        )

        resumedInstance.start()
        advanceUntilIdle()
        resumedInstance.join()

        assertEquals(expectedEvents, resumedEvents)
        assertEquals(TaskStatus.Completed, resumedInstance.currentState().tasks.single().status)
    }

    private fun stdlibBindings(host: NarrativeHost): KatariBindings {
        return NarrativeBindings {
            install(AllStdLibModules())
            register(NarrativeBuiltinFunctions.definitions(host))
        }
    }
}
