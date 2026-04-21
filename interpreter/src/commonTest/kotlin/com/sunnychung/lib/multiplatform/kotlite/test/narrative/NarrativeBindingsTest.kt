package com.sunnychung.lib.multiplatform.kotlite.test.narrative

import com.sunnychung.lib.multiplatform.kotlite.narrative.CallFunctionInstruction
import com.sunnychung.lib.multiplatform.kotlite.narrative.ImmediateNarrativeFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.narrative.KotliteNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.narrative.LiteralExpression
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeFunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeInstance
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeResultTarget
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskState
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeTaskStatus
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValue
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueRestoreContext
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.narrative.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.SuspendableNarrativeFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.narrative.TextValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.narrative.toKotlite
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.KotlinValueHolder
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class NarrativeBindingsTest {

    @Test
    fun kClassToKotliteRegistersHostObjectAndCodecForSnapshots() = runTest {
        val npcType = BindingTestNpcRef::class.toKotlite(typeId = "npc")
        val bindings = NarrativeBindings {
            registerHostType(
                type = npcType,
                snapshotClass = TestNpcValueSnapshot::class,
                snapshotSerializer = TestNpcValueSnapshot.serializer(),
                serialize = { value -> TestNpcValueSnapshot(value.id) },
                deserialize = { snapshot, _ -> BindingTestNpcRef("restored:${snapshot.id}") },
            )
            global("npc", BindingTestNpcRef("npc-1"))
        }
        val original = NarrativeState(
            programVersion = 1,
            tasks = listOf(
                NarrativeTaskState(
                    id = "main",
                    localVariables = mapOf("npc" to bindings.globals.getValue("npc")),
                )
            ),
            globals = bindings.globals,
        )

        val snapshot = bindings.snapshotCodec.serialize(original)
        val restored = bindings.snapshotCodec.restore(snapshot, object : NarrativeValueRestoreContext {})
        val restoredNpc = assertIs<NarrativeValue.HostObject>(
            restored.tasks.single().localVariables.getValue("npc")
        ).value as BindingTestNpcRef

        assertEquals(emptyMap(), snapshot.globals)
        assertEquals(emptyMap(), restored.globals)
        assertEquals("restored:npc-1", restoredNpc.id)
    }

    @Test
    fun suspendableFunctionDefinitionResumesViaDispatchCallback() = runTest {
        val bindings = NarrativeBindings {
            register(
                SuspendableNarrativeFunctionDefinition(
                    id = "promptFlag",
                    onDispatch = { _, _, resume -> resume(PromptFlagResponse(true)) },
                    onResume = { _, response, _ -> NarrativeValue.Bool((response as PromptFlagResponse).enabled) },
                )
            )
        }
        val instance = NarrativeInstance(
            program = NarrativeProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "promptFlag",
                        arguments = emptyList(),
                        resultTarget = NarrativeResultTarget.Variable("flag"),
                    ),
                )
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(NarrativeTaskStatus.Completed, task.status)
        assertEquals(NarrativeValue.Bool(true), task.localVariables.getValue("flag"))
    }

    @Test
    fun immediateFunctionDefinitionReturnsWithoutSuspension() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            register(
                ImmediateNarrativeFunctionDefinition(
                    id = "append",
                    execute = { arguments, _ ->
                        events += (arguments.single() as NarrativeValue.Text).value
                        NarrativeValue.Null
                    }
                )
            )
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    append("one")
                    append("two")
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("one", "two"), events)
    }

    @Test
    fun doubleLiteralIsSupportedInNarrativeExpressions() = runTest {
        val events = mutableListOf<String>()
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
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val weight = 2.345
                    "weight=${'$'}weight"
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("weight=2.345"), events)
    }

    @Test
    fun hostObjectCanBeRenderedByNarrateAsStringCompatibleValue() = runTest {
        val events = mutableListOf<String>()
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
            registerHostType(BindingNarrativeObject::class.toKotlite("binding_obj"))
            global("obj", BindingNarrativeObject("Test"))
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = "\"${'$'}obj\"",
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("BindingNarrativeObject(name=Test)"), events)
    }

    @Test
    fun suspendableMemberCanBeRegisteredForHostObjectAndCalledAsNavigation() = runTest {
        val bindings = NarrativeBindings {
            val actorType = BindingActor::class.toKotlite("binding_actor")
            registerHostType(actorType)
            registerSuspendableMember(
                type = actorType,
                name = "awaitReady",
                onDispatch = { receiver, _, _, resume ->
                    receiver.ready = true
                    resume(PromptFlagResponse(true))
                },
                onResume = { receiver, _, response, _ ->
                    NarrativeValue.Bool(receiver.ready && (response as PromptFlagResponse).enabled)
                },
            )
            global("actor", BindingActor())
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val ok = actor.awaitReady()
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(NarrativeTaskStatus.Completed, task.status)
        assertEquals(NarrativeValue.Bool(true), task.localVariables.getValue("ok"))
    }

    @Test
    fun executionEnvironmentFunctionsAreAutoAvailableInNarrative() = runTest {
        val events = mutableListOf<String>()
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
            registerKotliteFunction(
                CustomFunctionDefinition(
                    position = SourcePosition.NONE,
                    receiverType = null,
                    functionName = "shout",
                    returnType = "String",
                    parameterTypes = listOf(com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter("value", "String")),
                    executable = { interpreter, _, args, _ ->
                        StringValue((args.single() as StringValue).value.uppercase(), interpreter.symbolTable())
                    },
                )
            )
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val v = shout("hello")
                    "${'$'}v"
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(listOf("HELLO"), events)
    }

    @Test
    fun executionEnvironmentVarargFunctionsAreAvailableInNarrative() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            register(
                ImmediateNarrativeFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        val value = assertIs<NarrativeValue.HostObject>(arguments.single()).value as RuntimeValue
                        val holder = assertIs<KotlinValueHolder<*>>(value)
                        val elements = assertIs<List<*>>(holder.value).map { assertIs<RuntimeValue>(it).convertToString() }
                        events += elements.joinToString(prefix = "[", postfix = "]")
                        NarrativeValue.Null
                    }
                )
            )
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    capture(listOf(1, 2, 3, 4, 5))
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("[1, 2, 3, 4, 5]"), events)
    }

    @Test
    fun executionEnvironmentCollectionsSupportIndexPropertiesAndGenericExtensionFunctionsInNarrative() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            register(
                ImmediateNarrativeFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        events += when (val value = arguments.single()) {
                            NarrativeValue.Null -> "null"
                            is NarrativeValue.Bool -> value.value.toString()
                            is NarrativeValue.Int32 -> value.value.toString()
                            is NarrativeValue.Float64 -> value.value.toString()
                            is NarrativeValue.Text -> value.value
                            is NarrativeValue.Lambda -> "Lambda(${value.id})"
                            is NarrativeValue.HostObject -> (value.value as RuntimeValue).convertToString()
                        }
                        NarrativeValue.Null
                    }
                )
            )
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val list = listOf(1, 2, 3, 4, 5)
                    capture(list[0])
                    capture(list.size)
                    capture(list.min())
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("1", "5", "1"), events)
    }

    @Test
    fun executionEnvironmentMapOfSupportsToInfixInNarrative() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            register(
                ImmediateNarrativeFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        events += when (val value = arguments.single()) {
                            NarrativeValue.Null -> "null"
                            is NarrativeValue.Bool -> value.value.toString()
                            is NarrativeValue.Int32 -> value.value.toString()
                            is NarrativeValue.Float64 -> value.value.toString()
                            is NarrativeValue.Text -> value.value
                            is NarrativeValue.Lambda -> "Lambda(${value.id})"
                            is NarrativeValue.HostObject -> (value.value as RuntimeValue).convertToString()
                        }
                        NarrativeValue.Null
                    }
                )
            )
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val map = mapOf("hello" to 5)
                    capture(map["hello"])
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("5"), events)
    }

    @Test
    fun runtimeCollectionsRoundTripThroughSnapshotCodec() = runTest {
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val list = listOf(1, 2, 3)
                    val map = mapOf("hello" to 5)
                    "ok"
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val restored = bindings.snapshotCodec.restore(instance.serializeState())
        val locals = restored.tasks.single().localVariables
        val list = assertIs<NarrativeValue.HostObject>(locals.getValue("list")).value as RuntimeValue
        val map = assertIs<NarrativeValue.HostObject>(locals.getValue("map")).value as RuntimeValue

        assertEquals("List", list.type().name)
        assertEquals("Map", map.type().name)
        assertEquals("5", ((map as KotlinValueHolder<*>).value as Map<*, *>).values.single().let { (it as RuntimeValue).convertToString() })
    }

    @Test
    fun mutableCollectionsSupportIndexAssignmentInNarrative() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            register(
                ImmediateNarrativeFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        events += when (val value = arguments.single()) {
                            NarrativeValue.Null -> "null"
                            is NarrativeValue.Bool -> value.value.toString()
                            is NarrativeValue.Int32 -> value.value.toString()
                            is NarrativeValue.Float64 -> value.value.toString()
                            is NarrativeValue.Text -> value.value
                            is NarrativeValue.Lambda -> "Lambda(${value.id})"
                            is NarrativeValue.HostObject -> (value.value as RuntimeValue).convertToString()
                        }
                        NarrativeValue.Null
                    }
                )
            )
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val list = mutableListOf(1, 2, 3)
                    list[0] = 42
                    val map = mutableMapOf("hello" to 5)
                    map["hello"] = 7
                    capture(list[0])
                    capture(map["hello"])
                """.trimIndent(),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("42", "7"), events)
    }

    @Test
    fun userDefinedExtensionSetOperatorIsSupportedInNarrative() = runTest {
        val events = mutableListOf<String>()
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
        }
        val instance = NarrativeInstance(
            program = KotliteNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    operator fun String.set(index: String, value: Int): String {
                        "${'$'}this: ${'$'}index (${ '$' }value)"
                        return this
                    }
                    "Так, ну допустим"["И вот это"] = 1
                """.trimIndent().replace("${ '$' }", "$"),
            ),
            initialState = NarrativeState(
                programVersion = 1,
                tasks = listOf(NarrativeTaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(NarrativeTaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Так, ну допустим: И вот это (1)"), events)
    }
}

@Serializable
@SerialName("test_npc")
data class TestNpcValueSnapshot(
    val id: String,
) : NarrativeValueSnapshot()

data class BindingTestNpcRef(val id: String)

data class BindingNarrativeObject(val name: String)

data class BindingActor(var ready: Boolean = false)

data class PromptFlagResponse(val enabled: Boolean) : NarrativeFunctionResponse


