package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.CallFunctionInstruction
import com.sunnychung.lib.multiplatform.kotlite.katari.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.LiteralExpression
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeNoOpHost
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariStateSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.StateSnapshotCodec
import com.sunnychung.lib.multiplatform.kotlite.katari.StateSnapshotValidationException
import com.sunnychung.lib.multiplatform.kotlite.katari.ResultTarget
import com.sunnychung.lib.multiplatform.kotlite.katari.SlotSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.SlotValue
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatusSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.LambdaValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueCodec
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueReferenceSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueRestoreContext
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.SetResultInstruction
import com.sunnychung.lib.multiplatform.kotlite.katari.TextValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.VariableExpression
import com.sunnychung.lib.multiplatform.kotlite.katari.ROOT_CALL_FRAME_ID
import com.sunnychung.lib.multiplatform.kotlite.error.SemanticException
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallContext
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallDispatchContext
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallResult
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallable
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeHostValue
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeLambdaValue
import com.sunnychung.lib.multiplatform.kotlite.model.NullValue
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.model.TypeParameter
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules
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

    private fun symbolTable() = StateSnapshotCodec().symbolTable()

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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
                            LiteralExpression(StringValue("One", symbolTable())),
                            LiteralExpression(StringValue("Two", symbolTable())),
                        ),
                        resultTarget = ResultTarget.Slot(0),
                    ),
                )
            ),
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
                            LiteralExpression(NullValue),
                            LiteralExpression(StringValue("Visible", symbolTable())),
                        ),
                        resultTarget = ResultTarget.Variable("answer"),
                    ),
                )
            ),
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(1, seenOptions.single().size)
        assertEquals("Visible", seenOptions.single().single().text)
        assertEquals(StringValue("Visible", symbolTable()), instance.currentState().tasks.single().localVariables.getValue("answer"))
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
                        localVariables = mapOf("name" to StringValue("Igor", symbolTable())),
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
                            LiteralExpression(StringValue("ivan", symbolTable())),
                            LiteralExpression(StringValue("petr", symbolTable())),
                        ),
                        resultTarget = ResultTarget.Slot(0),
                    ),
                )
            ),
            executionEnvironment = NarrativeBuiltinFunctions.environment(
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
        assertEquals(StringValue("ivan", symbolTable()), task.localVariables.getValue("__narrative_slot_0"))
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
                        localVariables = mapOf("npc" to NarrativeHostValue("npc", "npc-1", symbolTable())),
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
        val valueRegistry = com.sunnychung.lib.multiplatform.kotlite.katari.KatariValueCodecRegistry(listOf(TestNpcCodec()))
        val codec = StateSnapshotCodec(valueCodecs = valueRegistry)
        val original = KatariState(
            programVersion = 1,
            tasks = listOf(
                TaskState(
                    id = "main",
                    instructionPointer = 0,
                    localVariables = mapOf("speaker" to NarrativeHostValue("npc", TestNpcRef("npc-2"), symbolTable())),
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
        assertEquals(TestNpcRef("restored:npc-2"), assertIs<NarrativeHostValue>(restored.tasks.single().localVariables.getValue("speaker")).value)
    }

    @Test
    fun snapshotPreservesSharedValuesAcrossTasks() = runTest {
        val codec = StateSnapshotCodec(
            valueCodecs = com.sunnychung.lib.multiplatform.kotlite.katari.KatariValueCodecRegistry(listOf(TestNpcCodec())),
        )
        val npc = TestNpcRef("npc-1")
        val original = KatariState(
            programVersion = 1,
            tasks = listOf(
                TaskState(
                    id = "main",
                    localVariables = mapOf("npc" to NarrativeHostValue("npc", npc, symbolTable())),
                ),
                TaskState(
                    id = "side",
                    localVariables = mapOf("npc" to NarrativeHostValue("npc", npc, symbolTable())),
                ),
            ),
        )

        val snapshot = codec.serialize(original)
        val restored = codec.restore(snapshot, TestRestoreContext)
        val mainNpc = assertIs<NarrativeHostValue>(
            restored.tasks.first { it.id == "main" }.localVariables.getValue("npc")
        ).value
        val sideNpc = assertIs<NarrativeHostValue>(
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
        val env = ExecutionEnvironment()
        NarrativeBuiltinFunctions.definitions(recordingNoOpHost()).forEach { env.registerNarrativeCallable(it) }
        env.registerNarrativeCallable(PromptFlagFunction)
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "promptFlag",
                        arguments = listOf(LiteralExpression(StringValue("Enable?", symbolTable()))),
                        resultTarget = ResultTarget.Slot(0),
                    ),
                )
            ),
            executionEnvironment = env,
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
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
                bindings = bindings
            ),
            executionEnvironment = bindings.executionEnvironment,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(listOf("Matched"), events)
        assertEquals(StringValue("Igor", symbolTable()), task.localVariables.getValue("name"))
        assertEquals(StringValue("Leave", symbolTable()), task.localVariables.getValue("action"))
        assertEquals(0, task.slots.size)
        assertTrue(task.localVariables.keys.none { it.startsWith("__narrative_slot_") })
    }

    @Test
    fun runtimeMarksTaskAsFailedAndJoinCompletesOnExecutionError() = runTest {
        val error = assertFailsWith<SemanticException> {
            KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    unknownFunction()
                    "never"
                """.trimIndent(),
            )
        }
        assertTrue(error.message!!.contains("No matching function `unknownFunction` found"))
    }

    @Test
    fun runtimeErrorInsideIfConditionUsesVariableExpressionColumn() = runTest {
        val error = assertFailsWith<SemanticException> {
            KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    fun check() {
                        if (money > 0) {
                            "never"
                        }
                    }
                    check()
                """.trimIndent(),
            )
        }
        assertTrue(error.message!!.contains("[<Narrative>:2:9]"))
        assertTrue(error.message!!.contains("Property `money` is not declared"))
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(listOf("sum=5"), events)
        assertEquals(IntValue(5, symbolTable()), task.localVariables.getValue("sum"))
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(listOf("result=8"), events)
        assertIs<NarrativeLambdaValue>(task.localVariables.getValue("twice"))
        assertEquals(IntValue(8, symbolTable()), task.localVariables.getValue("result"))
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(NarrativeNoOpHost),
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
        assertIs<NarrativeLambdaValue>(restoredLambda)
    }

    @Test
    fun lambdaCapturesExternalVariablesAcrossSnapshotWithoutDuplicatingValues() = runTest {
        val events = mutableListOf<String>()
        val gate = GateCallable()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
            register(gate)
        }
        val codec = StateSnapshotCodec(executionEnvironment = bindings.executionEnvironment)
        val program = KatariNarrativeProgram(
            filename = "<Narrative>",
            code = """
                val prefix = "v="
                val formatter = { value: Int -> "${'$'}prefix${'$'}value" }
                gate("before")
                val result = formatter(7)
                "${'$'}result"
            """.trimIndent(),
            bindings = bindings,
        )
        val instance = KatariInstance(
            program = program,
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        val snapshot = instance.serializeState()
        instance.cancel()

        val frameRefs = snapshot.tasks.single().callFrames.last().variableRefs
        val lambdaSnapshot = snapshot.values.getValue(frameRefs.getValue("formatter").valueId)
        assertIs<LambdaValueSnapshot>(lambdaSnapshot)
        assertEquals(frameRefs.getValue("prefix"), lambdaSnapshot.capturedVariableRefs.getValue("prefix"))

        val restored = KatariInstance(
            program = program,
            initialState = codec.restore(snapshot),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = codec,
            coroutineScope = this,
        )
        restored.start()
        advanceUntilIdle()
        gate.resume("before")
        advanceUntilIdle()
        restored.join()

        assertEquals(listOf("v=7"), events)
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(NarrativeNoOpHost),
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
        assertEquals(StringValue("Igor", symbolTable()), restored.tasks.single().localVariables.getValue("name"))
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
            install(AllStdLibModules())
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
                bindings
            ),
            executionEnvironment = bindings.executionEnvironment,
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
    fun asyncTaskRunsAlongsideMainBranchAndJoinReturnsResult() = runTest {
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
                    val task = async {
                        "async"
                        return "result"
                    }
                    task.start()
                    "main"
                    val result = task.join()
                    "joined ${'$'}result"
                """.trimIndent(),
            ),
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("main", "async", "joined result"), events)
    }

    @Test
    fun asyncTaskCanJumpToCheckpointInsideItsOwnBody() = runTest {
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
                    val task = async {
                        var i = 0
                        checkpoint loop
                        i = i + 1
                        if (i < 3) {
                            jump loop
                        }
                        return i
                    }
                    task.start()
                    val result = task.join()
                    "looped ${'$'}result"
                """.trimIndent(),
            ),
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("looped 3"), events)
    }

    @Test
    fun asyncTaskCannotJumpToCheckpointOutsideItsBody() {
        assertFailsWith<UnsupportedOperationException> {
            KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    checkpoint outer
                    val task = async {
                        jump outer
                    }
                    task.start()
                """.trimIndent(),
            )
        }
    }

    @Test
    fun raceReturnsFirstCompletedBranchAndStopsTheRest() = runTest {
        val events = mutableListOf<String>()
        val gate = GateCallable()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
            register(gate)
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val result = race {
                        gate("slow") -> "slow"
                        gate("fast") -> "fast"
                    }
                    "winner ${'$'}result"
                """.trimIndent(),
                bindings = bindings,
            ),
            executionEnvironment = bindings.executionEnvironment,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        gate.resume("fast")
        advanceUntilIdle()
        gate.resume("slow")
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("winner fast"), events)
        assertTrue(instance.currentState().tasks.any { it.id.endsWith("_0") && it.status == TaskStatus.Stopped })
    }

    @Test
    fun nestedAsyncAndRaceTasksResumeExpectedWinner() = runTest {
        val events = mutableListOf<String>()
        val gate = GateCallable()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
            register(gate)
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    fun nested(label: String) {
                        val task = async {
                            gate(label)
                            return label
                        }
                        task.start()
                        task.join()
                    }

                    val outer = async {
                        val result = race {
                            nested("slow") -> "slow"
                            nested("fast") -> "fast"
                        }
                        return result
                    }
                    outer.start()
                    val winner = outer.join()
                    "winner ${'$'}winner"
                """.trimIndent(),
                bindings = bindings,
            ),
            executionEnvironment = bindings.executionEnvironment,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        gate.resume("fast")
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("winner fast"), events)
    }

    @Test
    fun snapshotRestoresWaitingJoinAndAsyncTaskSuspension() = runTest {
        val events = mutableListOf<String>()
        val gate = GateCallable()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
            register(gate)
        }
        val program = KatariNarrativeProgram(
            filename = "<Narrative>",
            code = """
                val task = async {
                    gate("async")
                    return "done"
                }
                task.start()
                val result = task.join()
                "joined ${'$'}result"
            """.trimIndent(),
            bindings = bindings,
        )
        val codec = StateSnapshotCodec(executionEnvironment = bindings.executionEnvironment)
        val instance = KatariInstance(
            program = program,
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        val snapshot = instance.serializeState()
        instance.cancel()

        val restored = KatariInstance(
            program = program,
            initialState = codec.restore(snapshot),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = codec,
            coroutineScope = this,
        )
        restored.start()
        advanceUntilIdle()
        gate.resume("async")
        advanceUntilIdle()
        restored.join()

        assertEquals(listOf("joined done"), events)
    }

    @Test
    fun snapshotRestoresAsyncTaskIdCounterBeforeCreatingAnotherTask() = runTest {
        val events = mutableListOf<String>()
        val gate = GateCallable()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }
            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }
        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
            register(gate)
        }
        val program = KatariNarrativeProgram(
            filename = "<Narrative>",
            code = """
                val task1 = async {
                    return "first"
                }
                gate("snapshot")
                val task2 = async {
                    return "second"
                }
                task1.start()
                task2.start()
                val result1 = task1.join()
                val result2 = task2.join()
                "${'$'}result1 ${'$'}result2"
            """.trimIndent(),
            bindings = bindings,
        )
        val codec = StateSnapshotCodec(executionEnvironment = bindings.executionEnvironment)
        val instance = KatariInstance(
            program = program,
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = codec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        val snapshot = instance.serializeState()
        instance.cancel()

        val restored = KatariInstance(
            program = program,
            initialState = codec.restore(snapshot),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = codec,
            coroutineScope = this,
        )
        restored.start()
        advanceUntilIdle()
        gate.resume("snapshot")
        advanceUntilIdle()
        restored.join()

        assertEquals(listOf("first second"), events)
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
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
            executionEnvironment = NarrativeBuiltinFunctions.environment(host),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("result: 42"), events)
    }

    private fun recordingNoOpHost(): NarrativeHost = object : NarrativeHost {
        override fun narrate(text: String, resume: () -> Unit) = resume()
        override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = resume(options.firstOrNull()?.id ?: "")
        override fun readLine(question: String, resume: (String) -> Unit) = resume("")
    }
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

object PromptFlagFunction : NarrativeCallable {
    override val id: String = "promptFlag"
    override val receiverType: String? = null
    override val returnType: String = "Boolean"
    override val typeParameters: List<TypeParameter> = emptyList()
    override val valueParameters: List<CustomFunctionParameter> = listOf(
        CustomFunctionParameter("message", "String"),
    )

    override suspend fun startCall(
        arguments: List<RuntimeValue>,
        context: NarrativeCallContext,
    ): NarrativeCallResult {
        return NarrativeCallResult.Suspended
    }

    override suspend fun resumeCall(
        arguments: List<RuntimeValue>,
        response: FunctionResponse?,
        context: NarrativeCallContext,
    ): NarrativeCallResult {
        return NarrativeCallResult.Returned(BooleanValue(true, StateSnapshotCodec().symbolTable()))
    }

    override fun dispatch(
        arguments: List<RuntimeValue>,
        context: NarrativeCallDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        resume(null)
    }
}

class GateCallable : NarrativeCallable {
    private val pending = linkedMapOf<String, (FunctionResponse?) -> Unit>()

    override val id: String = "gate"
    override val receiverType: String? = null
    override val returnType: String = "Unit"
    override val typeParameters: List<TypeParameter> = emptyList()
    override val valueParameters: List<CustomFunctionParameter> = listOf(
        CustomFunctionParameter("name", "String"),
    )

    override suspend fun startCall(
        arguments: List<RuntimeValue>,
        context: NarrativeCallContext,
    ): NarrativeCallResult {
        return NarrativeCallResult.Suspended
    }

    override suspend fun resumeCall(
        arguments: List<RuntimeValue>,
        response: FunctionResponse?,
        context: NarrativeCallContext,
    ): NarrativeCallResult {
        return NarrativeCallResult.Returned(NullValue)
    }

    override fun dispatch(
        arguments: List<RuntimeValue>,
        context: NarrativeCallDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        val name = (arguments.single() as StringValue).value
        pending[name] = resume
    }

    fun resume(name: String) {
        pending.remove(name)?.invoke(null)
    }
}
