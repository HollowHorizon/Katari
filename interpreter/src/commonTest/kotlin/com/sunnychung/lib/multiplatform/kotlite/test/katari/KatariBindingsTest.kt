package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.CallFunctionInstruction
import com.sunnychung.lib.multiplatform.kotlite.katari.ImmediateKatariFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.FunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariTypes
import com.sunnychung.lib.multiplatform.kotlite.katari.ResultTarget
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariValue
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueRestoreContext
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.katari.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.SuspendableKatariFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.katari.asParameterType
import com.sunnychung.lib.multiplatform.kotlite.katari.toKatari
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter
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
        val npcType = BindingTestNpcRef::class.toKatari(typeId = "npc")
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
        val original = KatariState(
            programVersion = 1,
            tasks = listOf(
                TaskState(
                    id = "main",
                    localVariables = mapOf("npc" to bindings.globals.getValue("npc")),
                )
            ),
            globals = bindings.globals,
        )

        val snapshot = bindings.snapshotCodec.serialize(original)
        val restored = bindings.snapshotCodec.restore(snapshot, object : ValueRestoreContext {})
        val restoredNpc = assertIs<KatariValue.HostObject>(
            restored.tasks.single().localVariables.getValue("npc")
        ).value as BindingTestNpcRef

        assertEquals(emptyMap(), restored.globals)
        assertEquals("restored:npc-1", restoredNpc.id)
    }

    @Test
    fun suspendableFunctionDefinitionResumesViaDispatchCallback() = runTest {
        val bindings = NarrativeBindings {
            register(
                SuspendableKatariFunctionDefinition(
                    id = "promptFlag",
                    onDispatch = { _, _, resume -> resume(PromptFlagResponse(true)) },
                    onResume = { _, response, _ -> KatariValue.Bool((response as PromptFlagResponse).enabled) },
                )
            )
        }
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "promptFlag",
                        arguments = emptyList(),
                        resultTarget = ResultTarget.Variable("flag"),
                    ),
                )
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
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
        assertEquals(TaskStatus.Completed, task.status)
        assertEquals(KatariValue.Bool(true), task.localVariables.getValue("flag"))
    }

    @Test
    fun immediateFunctionDefinitionReturnsWithoutSuspension() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            register(
                ImmediateKatariFunctionDefinition(
                    id = "append",
                    execute = { arguments, _ ->
                        events += (arguments.single() as KatariValue.Text).value
                        KatariValue.Null
                    }
                )
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    append("one")
                    append("two")
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val weight = 2.345
                    "weight=${'$'}weight"
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
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
            registerHostType(BindingNarrativeObject::class.toKatari("binding_obj"))
            global("obj", BindingNarrativeObject("Test"))
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = "\"${'$'}obj\"",
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
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
            val actorType = BindingActor::class.toKatari("binding_actor")
            registerHostType(actorType)
            registerSuspendableMember(
                type = actorType,
                name = "awaitReady",
                onDispatch = { receiver, _, _, resume ->
                    receiver.ready = true
                    resume(PromptFlagResponse(true))
                },
                onResume = { receiver, _, response, _ ->
                    KatariValue.Bool(receiver.ready && (response as PromptFlagResponse).enabled)
                },
            )
            global("actor", BindingActor())
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val ok = actor.awaitReady()
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
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
        assertEquals(TaskStatus.Completed, task.status)
        assertEquals(KatariValue.Bool(true), task.localVariables.getValue("ok"))
    }

    @Test
    fun typedDslResolvesImmediateFunctionOverloadsByValueArgumentType() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            immediateFunction(
                name = "describe",
                valueParameters = listOf(KatariTypes.Int),
                execute = { _, arguments, _ ->
                    events += "int:${(arguments.single() as KatariValue.Int32).value}"
                    KatariValue.Null
                },
            )
            immediateFunction(
                name = "describe",
                valueParameters = listOf(KatariTypes.Text),
                execute = { _, arguments, _ ->
                    events += "text:${(arguments.single() as KatariValue.Text).value}"
                    KatariValue.Null
                },
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    describe(7)
                    describe("seven")
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            propertyRegistry = bindings.propertyRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("int:7", "text:seven"), events)
    }

    @Test
    fun typedDslResolvesMemberFunctionByDispatchReceiverType() = runTest {
        val events = mutableListOf<String>()
        val actorType = BindingActor::class.toKatari("binding_actor")
        val npcType = BindingTestNpcRef::class.toKatari("binding_npc")
        val bindings = NarrativeBindings {
            registerHostType(actorType)
            registerHostType(npcType)
            immediateMemberFunction(actorType, "label") { receiver, _, _ ->
                events += "actor:${receiver.ready}"
                KatariValue.Null
            }
            immediateMemberFunction(npcType, "label") { receiver, _, _ ->
                events += "npc:${receiver.id}"
                KatariValue.Null
            }
            global("actor", BindingActor(ready = true))
            global("npc", BindingTestNpcRef("npc-1"))
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    actor.label()
                    npc.label()
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            propertyRegistry = bindings.propertyRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("actor:true", "npc:npc-1"), events)
    }

    @Test
    fun typedDslSupportsComputedGlobalAndExtensionProperties() = runTest {
        var chapter = 1
        val actor = BindingActor()
        val actorType = BindingActor::class.toKatari("binding_actor")
        val bindings = NarrativeBindings {
            registerHostType(actorType)
            global("actor", actor)
            globalProperty(
                name = "chapter",
                getter = { KatariValue.Int32(chapter) },
                setter = { value -> chapter = (value as KatariValue.Int32).value },
            )
            extensionProperty(
                name = "ready",
                receiver = actorType.asParameterType(),
                valueType = KatariTypes.Boolean,
                getter = { receiver, _ ->
                    KatariValue.Bool(((receiver as KatariValue.HostObject).value as BindingActor).ready)
                },
                setter = { receiver, value, _ ->
                    ((receiver as KatariValue.HostObject).value as BindingActor).ready = (value as KatariValue.Bool).value
                },
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    chapter = chapter + 1
                    actor.ready = true
                    val observedChapter = chapter
                    val observedReady = actor.ready
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            propertyRegistry = bindings.propertyRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val locals = instance.currentState().tasks.single().localVariables
        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(2, chapter)
        assertEquals(true, actor.ready)
        assertEquals(KatariValue.Int32(2), locals.getValue("observedChapter"))
        assertEquals(KatariValue.Bool(true), locals.getValue("observedReady"))
        assertEquals(emptyMap(), bindings.globals - "actor")
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
                    parameterTypes = listOf(CustomFunctionParameter("value", "String")),
                    executable = { interpreter, _, args, _ ->
                        StringValue((args.single() as StringValue).value.uppercase(), interpreter.symbolTable())
                    },
                )
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val v = shout("hello")
                    "${'$'}v"
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
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
                ImmediateKatariFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        val value = assertIs<KatariValue.HostObject>(arguments.single()).value as RuntimeValue
                        val holder = assertIs<KotlinValueHolder<*>>(value)
                        val elements = assertIs<List<*>>(holder.value).map { assertIs<RuntimeValue>(it).convertToString() }
                        events += elements.joinToString(prefix = "[", postfix = "]")
                        KatariValue.Null
                    }
                )
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    capture(listOf(1, 2, 3, 4, 5))
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("[1, 2, 3, 4, 5]"), events)
    }

    @Test
    fun executionEnvironmentCollectionsSupportIndexPropertiesAndGenericExtensionFunctionsInNarrative() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            register(
                ImmediateKatariFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        events += when (val value = arguments.single()) {
                            KatariValue.Null -> "null"
                            is KatariValue.Bool -> value.value.toString()
                            is KatariValue.Int32 -> value.value.toString()
                            is KatariValue.Float64 -> value.value.toString()
                            is KatariValue.Text -> value.value
                            is KatariValue.Lambda -> "Lambda(${value.id})"
                            is KatariValue.HostObject -> (value.value as RuntimeValue).convertToString()
                        }
                        KatariValue.Null
                    }
                )
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val list = listOf(1, 2, 3, 4, 5)
                    capture(list[0])
                    capture(list.size)
                    capture(list.min())
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("1", "5", "1"), events)
    }

    @Test
    fun executionEnvironmentMapOfSupportsToInfixInNarrative() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            register(
                ImmediateKatariFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        events += when (val value = arguments.single()) {
                            KatariValue.Null -> "null"
                            is KatariValue.Bool -> value.value.toString()
                            is KatariValue.Int32 -> value.value.toString()
                            is KatariValue.Float64 -> value.value.toString()
                            is KatariValue.Text -> value.value
                            is KatariValue.Lambda -> "Lambda(${value.id})"
                            is KatariValue.HostObject -> (value.value as RuntimeValue).convertToString()
                        }
                        KatariValue.Null
                    }
                )
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val map = mapOf("hello" to 5)
                    capture(map["hello"])
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("5"), events)
    }

    @Test
    fun runtimeCollectionsRoundTripThroughSnapshotCodec() = runTest {
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val list = listOf(1, 2, 3)
                    val map = mapOf("hello" to 5)
                    "ok"
                """.trimIndent(),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
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
        val list = assertIs<KatariValue.HostObject>(locals.getValue("list")).value as RuntimeValue
        val map = assertIs<KatariValue.HostObject>(locals.getValue("map")).value as RuntimeValue

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
                ImmediateKatariFunctionDefinition(
                    id = "capture",
                    execute = { arguments, _ ->
                        events += when (val value = arguments.single()) {
                            KatariValue.Null -> "null"
                            is KatariValue.Bool -> value.value.toString()
                            is KatariValue.Int32 -> value.value.toString()
                            is KatariValue.Float64 -> value.value.toString()
                            is KatariValue.Text -> value.value
                            is KatariValue.Lambda -> "Lambda(${value.id})"
                            is KatariValue.HostObject -> (value.value as RuntimeValue).convertToString()
                        }
                        KatariValue.Null
                    }
                )
            )
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
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
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
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
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    operator fun String.set(index: String, value: Int): String {
                        "${'$'}this: ${'$'}index (${ '$' }value)"
                        return this
                    }
                    "Так, ну допустим"["И вот это"] = 1
                """.trimIndent().replace("${ '$' }", "$"),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            functionRegistry = bindings.functionRegistry,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Так, ну допустим: И вот это (1)"), events)
    }
}

@Serializable
@SerialName("test_npc")
data class TestNpcValueSnapshot(
    val id: String,
) : ValueSnapshot()

data class BindingTestNpcRef(val id: String)

data class BindingNarrativeObject(val name: String)

data class BindingActor(var ready: Boolean = false)

data class PromptFlagResponse(val enabled: Boolean) : FunctionResponse


