package com.sunnychung.lib.multiplatform.kotlite.test.narrative

import com.sunnychung.lib.multiplatform.kotlite.narrative.CallFunctionInstruction
import com.sunnychung.lib.multiplatform.kotlite.narrative.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.EntityValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.KotliteNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.narrative.LiteralExpression
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionContext
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionDispatchContext
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionRegistry
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionResult
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeInstance
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeStateSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeStateSnapshotCodec
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeResultTarget
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeSlotValue
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskStatus
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValue
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueCodec
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueCodecRegistry
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueRestoreContext
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class NarrativeInstanceTest {

    @Test
    fun instanceRunsBuiltinNarrateAndSayThroughHostCallbacks() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += "narrate:$text"
                resume()
            }

            override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) {
                events += "say:${assertIs<EntityValueSnapshot>(speaker).id}:$text"
                resume()
            }

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                error("choice should not be called")
            }

            override fun readLine(resume: (String) -> Unit) = error("readLine should not be called")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    "Narration"
                    npc.say("Hello")
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = mapOf("npc" to NarrativeValue.Entity("npc_1")),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("narrate:Narration", "say:npc_1:Hello"), events)
        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
    }

    @Test
    fun instanceRunsBuiltinChoiceWithoutExplicitResumeApi() = runTest {
        val chosen = CompletableDeferred<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                resume()
            }

            override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) {
                resume()
            }

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                chosen.complete(options.last().id)
                resume(options.last().id)
            }

            override fun readLine(resume: (String) -> Unit) = error("readLine should not be called")
        }
        val instance = NarrativeInstance(
            program = NarrativeProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "choice",
                        arguments = listOf(
                            LiteralExpression(NarrativeValue.Text("One")),
                            LiteralExpression(NarrativeValue.Text("Two")),
                        ),
                        resultTarget = NarrativeResultTarget.Slot(0),
                    ),
                )
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals("1", chosen.await())
        assertEquals(NarrativeSlotValue.Value(NarrativeValue.Text("1")), instance.currentState().tasks.single().slots.getValue(0))
    }

    @Test
    fun serializeStateIncludesSuspendedCallSnapshot() = runTest {
        var pendingResume: (() -> Unit)? = null
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                pendingResume = resume
            }

            override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(resume: (String) -> Unit) = error("unused")
        }
        val codec = NarrativeStateSnapshotCodec()
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram("<Narrative>", "\"Hello\""),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        val snapshot = instance.serializeState()
        val status = snapshot.tasks.single().status
        assertIs<com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskStatusSnapshot.SuspendedCall>(status)

        pendingResume!!.invoke()
        advanceUntilIdle()
        instance.join()
    }

    @Test
    fun snapshotRoundTripRestoresStateAndJson() = runTest {
        val codec = NarrativeStateSnapshotCodec()
        val original = com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeState(
            programVersion = 1,
            tasks = listOf(
                NarrativeTaskState(
                    id = "main",
                    instructionPointer = 2,
                    localVariables = emptyMap(),
                    slots = emptyMap(),
                    status = NarrativeTaskStatus.SuspendedCall(
                        resultTarget = null,
                        nextInstructionPointer = 2,
                    ),
                )
            ),
        )
        val snapshot = codec.serialize(original)
        val json = Json {
            serializersModule = codec.serializersModule()
            classDiscriminator = "kind"
        }
        val decoded = json.decodeFromString(
            NarrativeStateSnapshot.serializer(),
            json.encodeToString(NarrativeStateSnapshot.serializer(), snapshot),
        )

        assertEquals(original, codec.restore(decoded))
    }

    @Test
    fun cancellingInstanceDoesNotCancelExternallyProvidedScope() = runTest {
        val instance = NarrativeInstance(
            program = NarrativeProgram(emptyList()),
            coroutineScope = this,
        )

        instance.cancel()

        val second = NarrativeInstance(
            program = NarrativeProgram(emptyList()),
            coroutineScope = this,
        )
        second.start()
        advanceUntilIdle()

        assertEquals(NarrativeTaskStatus.Completed, second.currentState().tasks.single().status)
    }

    @Test
    fun snapshotFailsForExternalValueWithoutCodec() = runTest {
        val codec = NarrativeStateSnapshotCodec()
        val instance = NarrativeInstance(
            program = NarrativeProgram(emptyList()),
            initialState = com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = mapOf("npc" to NarrativeValue.HostObject("npc", "npc-1")),
            ),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        assertFailsWith<IllegalArgumentException> {
            instance.serializeState()
        }
    }

    @Test
    fun externalValueRestoreUsesSuspendDeserializer() = runTest {
        val valueRegistry = NarrativeValueCodecRegistry(listOf(TestNpcCodec()))
        val codec = NarrativeStateSnapshotCodec(valueCodecs = valueRegistry)
        val original = com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeState(
            programVersion = 1,
            tasks = listOf(
                NarrativeTaskState(
                    id = "main",
                    instructionPointer = 0,
                    localVariables = mapOf("speaker" to NarrativeValue.HostObject("npc", TestNpcRef("npc-2"))),
                    status = NarrativeTaskStatus.Ready,
                )
            ),
            globals = mapOf("npc" to NarrativeValue.HostObject("npc", TestNpcRef("npc-1"))),
        )

        val snapshot = codec.serialize(original)
        val json = Json {
            serializersModule = codec.serializersModule()
            classDiscriminator = "kind"
        }
        val restored = codec.restore(
            json.decodeFromString(
                NarrativeStateSnapshot.serializer(),
                json.encodeToString(NarrativeStateSnapshot.serializer(), snapshot),
            ),
            TestRestoreContext,
        )

        assertEquals(TestNpcRef("restored:npc-1"), assertIs<NarrativeValue.HostObject>(restored.globals.getValue("npc")).value)
        assertEquals(TestNpcRef("restored:npc-2"), assertIs<NarrativeValue.HostObject>(restored.tasks.single().localVariables.getValue("speaker")).value)
    }

    @Test
    fun customSuspendableFunctionResumesItselfThroughDispatchCallback() = runTest {
        val functionRegistry = NarrativeFunctionRegistry(
            listOf(
                *builtinFunctionDefinitions(),
                PromptFlagFunction,
            )
        )
        val instance = NarrativeInstance(
            program = NarrativeProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "promptFlag",
                        arguments = listOf(LiteralExpression(NarrativeValue.Text("Enable?"))),
                        resultTarget = NarrativeResultTarget.Slot(0),
                    ),
                )
            ),
            functionRegistry = functionRegistry,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(NarrativeSlotValue.Value(NarrativeValue.Bool(true)), instance.currentState().tasks.single().slots.getValue(0))
    }

    @Test
    fun variablesAndLoopsWorkInNarrativeCompiler() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }

            override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var i = 0
                    while (i < 3) {
                        "Tick"
                        i = i + 1
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("Tick", "Tick", "Tick"), events)
    }

    @Test
    fun functionCallExpressionsUseInternalSlots() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }

            override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) = error("unused")

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                resume(options.last().id)
            }

            override fun readLine(resume: (String) -> Unit) {
                resume("Igor")
            }
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val name = readLine()
                    val action = choose("Ask", "Leave")
                    if (name == "Igor" && action == "Leave") {
                        "Matched"
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(listOf("Matched"), events)
        assertEquals(NarrativeValue.Text("Igor"), task.localVariables.getValue("name"))
        assertEquals(NarrativeValue.Text("Leave"), task.localVariables.getValue("action"))
        assertEquals(0, task.slots.size)
    }
}

private fun builtinFunctionDefinitions(): Array<NarrativeFunctionDefinition> {
    val host = object : NarrativeHost {
        override fun narrate(text: String, resume: () -> Unit) = resume()
        override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) = resume()
        override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = resume(options.first().id)
        override fun readLine(resume: (String) -> Unit) = resume("")
    }
    val registry = NarrativeBuiltinFunctions.registry(host)
    return arrayOf(
        registry.definition("narrate"),
        registry.definition("say"),
        registry.definition("choice"),
    )
}

@Serializable
@SerialName("npc_ref")
data class TestNpcSnapshot(val entityId: String) : NarrativeValueSnapshot()

data class TestNpcRef(val id: String)

object TestRestoreContext : NarrativeValueRestoreContext

class TestNpcCodec : NarrativeValueCodec<TestNpcSnapshot> {
    override val typeId: String = "npc"
    override val snapshotClass: KClass<TestNpcSnapshot> = TestNpcSnapshot::class
    override val snapshotSerializer = TestNpcSnapshot.serializer()

    override fun serialize(value: Any): TestNpcSnapshot {
        return TestNpcSnapshot((value as TestNpcRef).id)
    }

    override suspend fun deserialize(snapshot: TestNpcSnapshot, context: NarrativeValueRestoreContext): Any {
        return TestNpcRef("restored:${snapshot.entityId}")
    }
}

object PromptFlagFunction : NarrativeFunctionDefinition {
    override val id: String = "promptFlag"

    override suspend fun startCall(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        return NarrativeFunctionResult.Suspended
    }

    override suspend fun resumeCall(
        arguments: List<NarrativeValue>,
        response: NarrativeFunctionResponse?,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        return NarrativeFunctionResult.Returned(NarrativeValue.Bool(true))
    }

    override fun dispatch(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionDispatchContext,
        resume: (NarrativeFunctionResponse?) -> Unit,
    ) {
        resume(null)
    }
}
