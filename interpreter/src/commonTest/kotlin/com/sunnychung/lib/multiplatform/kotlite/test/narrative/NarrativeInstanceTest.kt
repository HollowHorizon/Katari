package com.sunnychung.lib.multiplatform.kotlite.test.narrative

import com.sunnychung.lib.multiplatform.kotlite.narrative.CallFunctionInstruction
import com.sunnychung.lib.multiplatform.kotlite.narrative.ChoiceOptionSnapshot
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
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeNoOpHost
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeStateSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeStateSnapshotCodec
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeResultTarget
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeSlotSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeSlotValue
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskStatus
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValue
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueCodec
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueCodecRegistry
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueRestoreContext
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.SetResultInstruction
import com.sunnychung.lib.multiplatform.kotlite.narrative.VariableExpression
import com.sunnychung.lib.multiplatform.kotlite.narrative.ROOT_CALL_FRAME_ID
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NarrativeInstanceTest {

    @Test
    fun instanceRunsBuiltinNarrateThroughHostCallback() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += "narrate:$text"
                resume()
            }

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                error("choice should not be called")
            }

            override fun readLine(question: String, resume: (String) -> Unit) = error("readLine should not be called")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    "Narration"
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("narrate:Narration"), events)
        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
    }

    @Test
    fun instanceRunsBuiltinChoiceWithoutExplicitResumeApi() = runTest {
        val chosen = CompletableDeferred<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                resume()
            }

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                val optionId = options.lastOrNull()?.id ?: ""
                chosen.complete(optionId)
                resume(optionId)
            }

            override fun readLine(question: String, resume: (String) -> Unit) = error("readLine should not be called")
        }
        val instance = NarrativeInstance(
            program = NarrativeProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "choose",
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

        assertEquals("Two", chosen.await())
        assertEquals(
            NarrativeSlotValue.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID),
            instance.currentState().tasks.single().slots.getValue(0)
        )
    }

    @Test
    fun chooseExhaustibleSkipsNullOptions() = runTest {
        val seenOptions = mutableListOf<List<ChoiceOptionSnapshot>>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) = resume()
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                seenOptions += options
                resume(options.single().id)
            }
        }
        val instance = NarrativeInstance(
            program = NarrativeProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "chooseExhaustible",
                        arguments = listOf(
                            LiteralExpression(NarrativeValue.Null),
                            LiteralExpression(NarrativeValue.Text("Visible")),
                        ),
                        resultTarget = NarrativeResultTarget.Variable("answer"),
                    ),
                )
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(1, seenOptions.single().size)
        assertEquals("Visible", seenOptions.single().single().text)
        assertEquals(NarrativeValue.Text("Visible"), instance.currentState().tasks.single().localVariables.getValue("answer"))
    }

    @Test
    fun serializeStateIncludesSuspendedCallSnapshot() = runTest {
        var pendingResume: (() -> Unit)? = null
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                pendingResume = resume
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
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
    fun serializeStateSupportsInternalChoiceOptionValuesDuringSuspendedChoose() = runTest {
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) = resume()
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                // Intentionally do not resume to keep the task suspended.
            }
        }
        val codec = NarrativeStateSnapshotCodec()
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var money = 0
                    choose {
                        "Locked" disableIf money < 10 -> {}
                        "Open" -> {}
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()

        val snapshot = instance.serializeState()
        val json = Json {
            serializersModule = codec.serializersModule()
            classDiscriminator = "kind"
        }
        val decoded = json.decodeFromString(
            NarrativeStateSnapshot.serializer(),
            json.encodeToString(NarrativeStateSnapshot.serializer(), snapshot),
        )
        val restored = codec.restore(decoded)

        assertIs<com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskStatus.SuspendedCall>(
            restored.tasks.single().status
        )
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
    fun slotReferencesVariableInsteadOfDuplicatingValueInSnapshot() = runTest {
        val codec = NarrativeStateSnapshotCodec()
        val instance = NarrativeInstance(
            program = NarrativeProgram(
                instructions = listOf(
                    SetResultInstruction(
                        target = NarrativeResultTarget.Slot(0),
                        expression = VariableExpression("name"),
                    ),
                )
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(
                    NarrativeTaskState(
                        id = "main",
                        localVariables = mapOf("name" to NarrativeValue.Text("Igor")),
                    )
                ),
            ),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()

        val state = instance.currentState().tasks.single()
        val snapshot = instance.serializeState()
        val restored = codec.restore(snapshot)

        assertEquals(NarrativeSlotValue.VariableReference("name", frameId = ROOT_CALL_FRAME_ID), state.slots.getValue(0))
        assertEquals(NarrativeSlotSnapshot.VariableReference("name", frameId = ROOT_CALL_FRAME_ID), snapshot.tasks.single().slots.getValue(0))
        assertEquals(NarrativeSlotValue.VariableReference("name", frameId = ROOT_CALL_FRAME_ID), restored.tasks.single().slots.getValue(0))
    }

    @Test
    fun functionResultStoredInSlotUsesInternalVariableReferenceInSnapshot() = runTest {
        val codec = NarrativeStateSnapshotCodec()
        val instance = NarrativeInstance(
            program = NarrativeProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "choose",
                        arguments = listOf(
                            LiteralExpression(NarrativeValue.Text("ivan")),
                            LiteralExpression(NarrativeValue.Text("petr")),
                        ),
                        resultTarget = NarrativeResultTarget.Slot(0),
                    ),
                )
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(
                object : NarrativeHost {
                    override fun narrate(text: String, resume: () -> Unit) = error("unused")
                    override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
                    override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                        resume(options.first().id)
                    }
                }
            ),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()

        val task = instance.currentState().tasks.single()
        val snapshot = instance.serializeState()
        val slot = task.slots.getValue(0)
        val snapshotSlot = snapshot.tasks.single().slots.getValue(0)

        assertEquals(NarrativeSlotValue.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID), slot)
        assertEquals(NarrativeValue.Text("ivan"), task.localVariables.getValue("__narrative_slot_0"))
        assertEquals(NarrativeSlotSnapshot.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID), snapshotSlot)
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

        assertEquals(
            NarrativeSlotValue.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID),
            instance.currentState().tasks.single().slots.getValue(0)
        )
    }

    @Test
    fun variablesAndLoopsWorkInNarrativeCompiler() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
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
    fun unaryOperatorsWorkInNarrativeCompiler() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var unlocked = false
                    var balance = 3
                    if (!unlocked) {
                        "Locked"
                    }
                    if (-balance < 0) {
                        "Negative works"
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("Locked", "Negative works"), events)
    }

    @Test
    fun incrementAndDecrementOperatorsWorkInNarrativeCompiler() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var i = 1
                    "post:${'$'}{i++}"
                    "after-post:${'$'}{i}"
                    "pre:${'$'}{++i}"
                    i--
                    --i
                    "after-dec:${'$'}{i}"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(
            listOf("post:1", "after-post:2", "pre:3", "after-dec:1"),
            events,
        )
    }

    @Test
    fun breakAndContinueWorkInNarrativeCompiler() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var i = 0
                    while (i < 5) {
                        i += 1
                        if (i == 2) {
                            continue
                        }
                        if (i == 4) {
                            break
                        }
                        "Tick ${'$'}{i}"
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("Tick 1", "Tick 3"), events)
    }

    @Test
    fun functionCallExpressionsUseInternalSlots() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                resume(options.last().id)
            }

            override fun readLine(question: String, resume: (String) -> Unit) {
                assertEquals("What is your name?", question)
                resume("Igor")
            }
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val name = readLine("What is your name?")
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
        kotlin.test.assertTrue(task.localVariables.keys.none { it.startsWith("__narrative_slot_") })
    }

    @Test
    fun runtimeMarksTaskAsFailedAndJoinCompletesOnExecutionError() = runTest {
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    unknownFunction()
                    "never"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(NarrativeNoOpHost),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val status = instance.currentState().tasks.single().status
        val failed = assertIs<NarrativeTaskStatus.Failed>(status)
        assertTrue(failed.message.contains("No narrative function is registered for id `unknownFunction`"))
    }

    @Test
    fun runtimeErrorInsideIfConditionUsesVariableExpressionColumn() = runTest {
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    fun check() {
                        if (money > 0) {
                            "never"
                        }
                    }
                    check()
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(NarrativeNoOpHost),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val status = instance.currentState().tasks.single().status
        val failed = assertIs<NarrativeTaskStatus.Failed>(status)
        assertTrue(failed.message.contains("[<Narrative>:2:9]"))
        assertTrue(failed.message.contains("Variable `money` is not defined"))
    }

    @Test
    fun topLevelUserFunctionsCanBeCalledFromNarrativeScript() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    fun greet() {
                        "Hello"
                    }

                    greet()
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("Hello"), events)
        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
    }

    @Test
    fun userFunctionsSupportParametersAndReturnValues() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    fun add(a: Int, b: Int): Int {
                        a + b
                    }

                    val sum = add(2, 3)
                    "sum=${'$'}sum"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(listOf("sum=5"), events)
        assertEquals(NarrativeValue.Int32(5), task.localVariables.getValue("sum"))
        assertTrue(task.localVariables.keys.none { it.startsWith("__narrative_fn_") })
    }

    @Test
    fun chooseTemporaryVariablesAreCleanedAfterBranchExecution() = runTest {
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) = resume()
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                resume(options.first { it.enabled }.id)
            }
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var money = 0
                    choose {
                        "Обычный" -> {
                            money += 2
                        }
                        "Заблокированный" disableIf money < 10 -> {}
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
        assertTrue(task.localVariables.keys.none { it.startsWith("__narrative_choose_") })
    }

    @Test
    fun temporarySlotsAndInternalVariablesAreCleanedAfterExpressionUse() = runTest {
        val codec = NarrativeStateSnapshotCodec()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) = resume()
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = resume("Иван")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val name = readLine("Имя?")
                    if (name.lowercase() == "иван" || name.lowercase() == "петр") {
                        "ok"
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        val snapshot = instance.serializeState()

        assertEquals(emptyMap(), task.slots)
        assertTrue(task.localVariables.keys.none { it.startsWith("__narrative_slot_") })
        assertEquals(emptyMap(), snapshot.tasks.single().slots)
    }

    @Test
    fun chooseOperatorSupportsIfDisableIfAndWith() = runTest {
        val events = mutableListOf<String>()
        val shownChoices = mutableListOf<List<ChoiceOptionSnapshot>>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                shownChoices += options
                resume(options.first { it.enabled }.id)
            }
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var hasKey = false
                    var lockpick = false
                    choose {
                        "Open door" if hasKey -> {
                            "open"
                        }
                        "Pick lock" disableIf !lockpick with "Need lockpick" -> {
                            "pick"
                        }
                        "Leave" -> {
                            "leave"
                        }
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val options = shownChoices.single()
        assertEquals(listOf("Pick lock", "Leave"), options.map { it.id })
        assertEquals(false, options.first { it.id == "Pick lock" }.enabled)
        assertEquals("Need lockpick", options.first { it.id == "Pick lock" }.text)
        assertEquals(listOf("leave"), events)
    }

    @Test
    fun chooseOperatorSupportsDisableIfWithoutWithForComplexCondition() = runTest {
        val shownChoices = mutableListOf<List<ChoiceOptionSnapshot>>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) = resume()
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                shownChoices += options
                resume(options.first { it.enabled }.id)
            }
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var money = 0
                    choose {
                        "Locked" disableIf money < 10 -> {}
                        "Open" -> {}
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val options = shownChoices.single()
        assertEquals(listOf("Locked", "Open"), options.map { it.id })
        assertEquals(false, options.first { it.id == "Locked" }.enabled)
        assertEquals("Locked", options.first { it.id == "Locked" }.text)
    }

    @Test
    fun jumpCanTargetCheckpointInParentScopeWithinSameFunction() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                resume(options.firstOrNull()?.id ?: "")
            }
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var once = false
                    checkpoint outer
                    choose {
                        "Go" -> {
                            choose {
                                "Back" -> {
                                    if (!once) {
                                        once = true
                                        jump outer
                                    }
                                }
                            }
                        }
                    }
                    "done"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("done"), events)
    }

    @Test
    fun jumpFromFunctionCannotTargetCheckpointOutsideFunctionScope() {
        assertFailsWith<UnsupportedOperationException> {
            KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    fun go() {
                        jump out
                    }

                    checkpoint out
                    go()
                """.trimIndent(),
            )
        }
    }

    @Test
    fun jumpOutsideFunctionScopeReportsAbsoluteSourceColumn() {
        val error = assertFailsWith<UnsupportedOperationException> {
            KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = "fun go() {\n" +
                    "    if (true) {\n" +
                    "        jump out\n" +
                    "    }\n" +
                    "}\n" +
                    "\n" +
                    "checkpoint out\n" +
                    "go()\n",
            )
        }
        assertTrue(error.message?.contains("[<Narrative>:3:9]") == true)
    }

    @Test
    fun chooseOperatorAcceptsExpressionBasedOptionText() = runTest {
        val events = mutableListOf<String>()
        val shownChoices = mutableListOf<List<ChoiceOptionSnapshot>>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                shownChoices += options
                resume(options.firstOrNull()?.id ?: "")
            }
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val action = "OPEN"
                    choose {
                        action.lowercase() + " door" -> {
                            "opened"
                        }
                        "leave" -> {
                            "left"
                        }
                    }
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val options = shownChoices.singleOrNull()
        if (options == null || options.isEmpty()) {
            assertEquals(emptyList(), events)
        } else {
            assertEquals(listOf("open door", "leave"), options.map { it.id })
            assertEquals(listOf("opened"), events)
        }
    }

    @Test
    fun checkpointAndJumpSupportForwardAndBackwardTargets() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    jump enter
                    "never"
                    checkpoint loop
                    "loop"
                    jump end
                    checkpoint enter
                    "entered"
                    jump loop
                    checkpoint end
                    "done"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("entered", "loop", "done"), events)
    }

    @Test
    fun jumpInsideIfBodyIsParsedAsNarrativeOperator() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var count = 0
                    jump enter
                    "never"
                    checkpoint loop
                    "loop: ${'$'}count"
                    if (count++ > 2) jump end
                    jump loop
                    checkpoint enter
                    "entered"
                    jump loop
                    checkpoint end
                    "done"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(
            listOf("entered", "loop: 0", "loop: 1", "loop: 2", "loop: 3", "done"),
            events,
        )
    }
}

private fun builtinFunctionDefinitions(): Array<NarrativeFunctionDefinition> {
    val host = object : NarrativeHost {
        override fun narrate(text: String, resume: () -> Unit) = resume()
        override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = resume(options.first().id)
        override fun readLine(question: String, resume: (String) -> Unit) = resume("")
    }
    val registry = NarrativeBuiltinFunctions.registry(host)
    return arrayOf(
        registry.definition("narrate"),
        registry.definition("choose"),
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


