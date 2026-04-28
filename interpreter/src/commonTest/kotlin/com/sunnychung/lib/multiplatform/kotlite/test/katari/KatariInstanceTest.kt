package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.CallFunctionInstruction
import com.sunnychung.lib.multiplatform.kotlite.katari.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.LiteralExpression
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariFunctionContext
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariCallableSignature
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariFunctionDispatchContext
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariFunctionRegistry
import com.sunnychung.lib.multiplatform.kotlite.katari.FunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.katari.FunctionResult
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeNoOpHost
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariStateSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariTypes
import com.sunnychung.lib.multiplatform.kotlite.katari.StateSnapshotCodec
import com.sunnychung.lib.multiplatform.kotlite.katari.StateSnapshotValidationException
import com.sunnychung.lib.multiplatform.kotlite.katari.ResultTarget
import com.sunnychung.lib.multiplatform.kotlite.katari.SlotSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.SlotValue
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatusSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariValue
import com.sunnychung.lib.multiplatform.kotlite.katari.LambdaValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueCodec
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariValueCodecRegistry
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueReferenceSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.asValueParameter
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueRestoreContext
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.SetResultInstruction
import com.sunnychung.lib.multiplatform.kotlite.katari.TextValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.VariableExpression
import com.sunnychung.lib.multiplatform.kotlite.katari.ROOT_CALL_FRAME_ID
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KatariInstanceTest {

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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    "Narration"
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("narrate:Narration"), events)
        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
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
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "choose",
                        arguments = listOf(
                            LiteralExpression(KatariValue.Text("One")),
                            LiteralExpression(KatariValue.Text("Two")),
                        ),
                        resultTarget = ResultTarget.Slot(0),
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
            SlotValue.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID),
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
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "chooseExhaustible",
                        arguments = listOf(
                            LiteralExpression(KatariValue.Null),
                            LiteralExpression(KatariValue.Text("Visible")),
                        ),
                        resultTarget = ResultTarget.Variable("answer"),
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
        assertEquals(KatariValue.Text("Visible"), instance.currentState().tasks.single().localVariables.getValue("answer"))
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
        val codec = StateSnapshotCodec()
        val instance = KatariInstance(
            program = KatariNarrativeProgram("<Narrative>", "\"Hello\""),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        val snapshot = instance.serializeState()
        val status = snapshot.tasks.single().status
        assertIs<TaskStatusSnapshot.SuspendedCall>(status)

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
        val codec = StateSnapshotCodec()
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
            KatariStateSnapshot.serializer(),
            json.encodeToString(KatariStateSnapshot.serializer(), snapshot),
        )
        val restored = codec.restore(decoded)

        assertIs<TaskStatus.SuspendedCall>(
            restored.tasks.single().status
        )
    }

    @Test
    fun snapshotRoundTripRestoresStateAndJson() = runTest {
        val codec = StateSnapshotCodec()
        val original = KatariState(
            programVersion = 1,
            tasks = listOf(
                TaskState(
                    id = "main",
                    instructionPointer = 2,
                    localVariables = emptyMap(),
                    slots = emptyMap(),
                    status = TaskStatus.SuspendedCall(
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
            KatariStateSnapshot.serializer(),
            json.encodeToString(KatariStateSnapshot.serializer(), snapshot),
        )

        assertEquals(original, codec.restore(decoded))
    }

    @Test
    fun slotReferencesVariableInsteadOfDuplicatingValueInSnapshot() = runTest {
        val codec = StateSnapshotCodec()
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    SetResultInstruction(
                        target = ResultTarget.Slot(0),
                        expression = VariableExpression("name"),
                    ),
                )
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(
                    TaskState(
                        id = "main",
                        localVariables = mapOf("name" to KatariValue.Text("Igor")),
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

        assertEquals(SlotValue.VariableReference("name", frameId = ROOT_CALL_FRAME_ID), state.slots.getValue(0))
        assertEquals(SlotSnapshot.VariableReference("name", frameId = ROOT_CALL_FRAME_ID), snapshot.tasks.single().slots.getValue(0))
        assertEquals(SlotValue.VariableReference("name", frameId = ROOT_CALL_FRAME_ID), restored.tasks.single().slots.getValue(0))
    }

    @Test
    fun functionResultStoredInSlotUsesInternalVariableReferenceInSnapshot() = runTest {
        val codec = StateSnapshotCodec()
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "choose",
                        arguments = listOf(
                            LiteralExpression(KatariValue.Text("ivan")),
                            LiteralExpression(KatariValue.Text("petr")),
                        ),
                        resultTarget = ResultTarget.Slot(0),
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

        assertEquals(SlotValue.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID), slot)
        assertEquals(KatariValue.Text("ivan"), task.localVariables.getValue("__narrative_slot_0"))
        assertEquals(SlotSnapshot.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID), snapshotSlot)
    }

    @Test
    fun cancellingInstanceDoesNotCancelExternallyProvidedScope() = runTest {
        val instance = KatariInstance(
            program = KatariProgram(emptyList()),
            coroutineScope = this,
        )

        instance.cancel()

        val second = KatariInstance(
            program = KatariProgram(emptyList()),
            coroutineScope = this,
        )
        second.start()
        advanceUntilIdle()

        assertEquals(TaskStatus.Completed, second.currentState().tasks.single().status)
    }

    @Test
    fun snapshotFailsForExternalValueWithoutCodec() = runTest {
        val codec = StateSnapshotCodec()
        val instance = KatariInstance(
            program = KatariProgram(emptyList()),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(
                    TaskState(
                        id = "main",
                        localVariables = mapOf("npc" to KatariValue.HostObject("npc", "npc-1")),
                    )
                ),
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
        val valueRegistry = KatariValueCodecRegistry(listOf(TestNpcCodec()))
        val codec = StateSnapshotCodec(valueCodecs = valueRegistry)
        val original = KatariState(
            programVersion = 1,
            tasks = listOf(
                TaskState(
                    id = "main",
                    instructionPointer = 0,
                    localVariables = mapOf("speaker" to KatariValue.HostObject("npc", TestNpcRef("npc-2"))),
                    status = TaskStatus.Ready,
                )
            ),
        )

        val snapshot = codec.serialize(original)
        val json = Json {
            serializersModule = codec.serializersModule()
            classDiscriminator = "kind"
        }
        val restored = codec.restore(
            json.decodeFromString(
                KatariStateSnapshot.serializer(),
                json.encodeToString(KatariStateSnapshot.serializer(), snapshot),
            ),
            TestRestoreContext,
        )

        assertEquals(emptyMap(), restored.globals)
        assertEquals(TestNpcRef("restored:npc-2"), assertIs<KatariValue.HostObject>(restored.tasks.single().localVariables.getValue("speaker")).value)
    }

    @Test
    fun snapshotPreservesSharedValuesAcrossTasks() = runTest {
        val codec = StateSnapshotCodec(
            valueCodecs = KatariValueCodecRegistry(listOf(TestNpcCodec())),
        )
        val npc = TestNpcRef("npc-1")
        val original = KatariState(
            programVersion = 1,
            tasks = listOf(
                TaskState(
                    id = "main",
                    localVariables = mapOf("npc" to KatariValue.HostObject("npc", npc)),
                ),
                TaskState(
                    id = "side",
                    localVariables = mapOf("npc" to KatariValue.HostObject("npc", npc)),
                ),
            ),
        )

        val snapshot = codec.serialize(original)
        val restored = codec.restore(snapshot, TestRestoreContext)
        val mainNpc = assertIs<KatariValue.HostObject>(
            restored.tasks.first { it.id == "main" }.localVariables.getValue("npc")
        ).value
        val sideNpc = assertIs<KatariValue.HostObject>(
            restored.tasks.first { it.id == "side" }.localVariables.getValue("npc")
        ).value

        assertEquals(
            snapshot.tasks.first { it.id == "main" }.variableRefs.getValue("npc"),
            snapshot.tasks.first { it.id == "side" }.variableRefs.getValue("npc"),
        )
        assertEquals(1, snapshot.values.size)
        assertSame(mainNpc, sideNpc)
    }

    @Test
    fun customSuspendableFunctionResumesItselfThroughDispatchCallback() = runTest {
        val functionRegistry = KatariFunctionRegistry(
            listOf(
                *builtinFunctionDefinitions(),
                PromptFlagFunction,
            )
        )
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "promptFlag",
                        arguments = listOf(LiteralExpression(KatariValue.Text("Enable?"))),
                        resultTarget = ResultTarget.Slot(0),
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
            SlotValue.VariableReference("__narrative_slot_0", frameId = ROOT_CALL_FRAME_ID),
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        assertEquals(KatariValue.Text("Igor"), task.localVariables.getValue("name"))
        assertEquals(KatariValue.Text("Leave"), task.localVariables.getValue("action"))
        assertEquals(0, task.slots.size)
        kotlin.test.assertTrue(task.localVariables.keys.none { it.startsWith("__narrative_slot_") })
    }

    @Test
    fun runtimeMarksTaskAsFailedAndJoinCompletesOnExecutionError() = runTest {
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val failed = assertIs<TaskStatus.Failed>(status)
        assertTrue(failed.message.contains("No narrative function is registered for id `unknownFunction`"))
    }

    @Test
    fun runtimeErrorInsideIfConditionUsesVariableExpressionColumn() = runTest {
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val failed = assertIs<TaskStatus.Failed>(status)
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        assertEquals(KatariValue.Int32(5), task.localVariables.getValue("sum"))
        assertTrue(task.localVariables.keys.none { it.startsWith("__narrative_fn_") })
    }

    @Test
    fun lambdaLiteralCanBeStoredAndInvokedInsideNarrativeScript() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val twice = { value: Int -> value + value }
                    val result = twice(4)
                    "result=${'$'}result"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(listOf("result=8"), events)
        assertIs<KatariValue.Lambda>(task.localVariables.getValue("twice"))
        assertEquals(KatariValue.Int32(8), task.localVariables.getValue("result"))
    }

    @Test
    fun snapshotRoundTripPreservesLambdaValues() = runTest {
        val codec = StateSnapshotCodec()
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val formatter = { value: Int -> "v=${'$'}value" }
                    "ok"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(NarrativeNoOpHost),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val snapshot = instance.serializeState()
        val snapshotTask = snapshot.tasks.single()
        val lambdaSnapshot = snapshot.values.getValue(
            snapshotTask.callFrames.last().variableRefs.getValue("formatter").valueId,
        )
        val restored = codec.restore(snapshot)
        val restoredLambda = restored.tasks.single().localVariables.getValue("formatter")

        assertIs<LambdaValueSnapshot>(lambdaSnapshot)
        assertIs<KatariValue.Lambda>(restoredLambda)
    }

    @Test
    fun snapshotStoresLocalsOnlyInsideCallFrames() = runTest {
        val codec = StateSnapshotCodec()
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val name = "Igor"
                    "ok"
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(NarrativeNoOpHost),
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val snapshot = instance.serializeState()
        val restored = codec.restore(snapshot)
        val snapshotTask = snapshot.tasks.single()
        val nameSnapshot = snapshot.values.getValue(
            snapshotTask.callFrames.last().variableRefs.getValue("name").valueId,
        )

        val json = Json {
            serializersModule = codec.serializersModule()
            classDiscriminator = "kind"
        }.encodeToString(KatariStateSnapshot.serializer(), snapshot)

        assertTrue(!json.contains("localVariables"))
        assertTrue(!json.contains("globals"))
        assertTrue(json.contains("variableRefs"))
        assertTrue(json.contains("values"))
        assertEquals(
            TextValueSnapshot("Igor"),
            nameSnapshot,
        )
        assertEquals(KatariValue.Text("Igor"), restored.tasks.single().localVariables.getValue("name"))
    }

    @Test
    fun restoreFailsWithDiagnosticsForMissingSnapshotValueReference() = runTest {
        val codec = StateSnapshotCodec()
        val error = assertFailsWith<StateSnapshotValidationException> {
            codec.restore(
                KatariStateSnapshot(
                    programVersion = 1,
                    values = emptyMap(),
                    tasks = listOf(
                        TaskSnapshot(
                            id = "main",
                            instructionPointer = 0,
                            variableRefs = mapOf("name" to ValueReferenceSnapshot(999)),
                            callFrames = emptyList(),
                            nextCallFrameId = ROOT_CALL_FRAME_ID + 1,
                            slots = emptyMap(),
                            status = TaskStatusSnapshot.Ready,
                        )
                    ),
                )
            )
        }

        assertEquals("tasks[0].variableRefs[name]", error.diagnostics.single().path)
        assertTrue(error.message!!.contains("missing snapshot value `999`"))
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val codec = StateSnapshotCodec()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) = resume()
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = resume("Иван")
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
            KatariNarrativeProgram(
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
            KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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

    @Test
    fun lambdaParameterShadowsOuterVariable() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    var a = 1
                    val f = { a: Int ->
                        "result: ${'$'}a"
                    }
                    f(42)
                """.trimIndent(),
            ),
            functionRegistry = NarrativeBuiltinFunctions.registry(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("result: 42"), events)
    }
}

private fun builtinFunctionDefinitions(): Array<KatariFunctionDefinition> {
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
data class TestNpcSnapshot(val entityId: String) : ValueSnapshot()

data class TestNpcRef(val id: String)

object TestRestoreContext : ValueRestoreContext

class TestNpcCodec : ValueCodec<TestNpcSnapshot> {
    override val typeId: String = "npc"
    override val snapshotClass: KClass<TestNpcSnapshot> = TestNpcSnapshot::class
    override val snapshotSerializer = TestNpcSnapshot.serializer()

    override fun serialize(value: Any): TestNpcSnapshot {
        return TestNpcSnapshot((value as TestNpcRef).id)
    }

    override suspend fun deserialize(snapshot: TestNpcSnapshot, context: ValueRestoreContext): Any {
        return TestNpcRef("restored:${snapshot.entityId}")
    }
}

object PromptFlagFunction : KatariFunctionDefinition {
    override val id: String = "promptFlag"
    override val signature: KatariCallableSignature = KatariCallableSignature(
        valueParameters = listOf(KatariTypes.Text.asValueParameter("message")),
        returnType = KatariTypes.Boolean,
    )

    override suspend fun startCall(
        arguments: List<KatariValue>,
        context: KatariFunctionContext,
    ): FunctionResult {
        return FunctionResult.Suspended
    }

    override suspend fun resumeCall(
        arguments: List<KatariValue>,
        response: FunctionResponse?,
        context: KatariFunctionContext,
    ): FunctionResult {
        return FunctionResult.Returned(KatariValue.Bool(true))
    }

    override fun dispatch(
        arguments: List<KatariValue>,
        context: KatariFunctionDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        resume(null)
    }
}
