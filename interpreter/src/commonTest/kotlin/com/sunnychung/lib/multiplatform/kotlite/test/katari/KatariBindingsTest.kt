package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.*
import com.sunnychung.lib.multiplatform.kotlite.model.*
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
        val bindings = NarrativeBindings {
            registerHostType(
                BindingTestNpcRef::class,
                "npc",
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
        val restoredNpc = assertIs<NarrativeHostValue>(
            restored.tasks.single().localVariables.getValue("npc")
        ).value as BindingTestNpcRef

        assertEquals(emptyMap(), restored.globals)
        assertEquals("restored:npc-1", restoredNpc.id)
    }

    @Test
    fun suspendableFunctionDefinitionResumesViaDispatchCallback() = runTest {
        val bindings = NarrativeBindings {
            suspendableFunction(
                "promptFlag",
                returnType = "Boolean",
                onDispatch = { _, _, resume ->
                    resume(PromptFlagResponse(true))
                },
                onResume = { _, response, ctx ->
                    BooleanValue(
                        (response as PromptFlagResponse).enabled,
                        ctx.symbolTable
                    )
                })
        }
        val instance = KatariInstance(
            program = KatariProgram(
                instructions = listOf(
                    CallFunctionInstruction(
                        functionId = "promptFlag",
                        arguments = emptyList(),
                        resultTarget = ResultTarget.Variable("flag"),
                    ),
                ),
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(TaskStatus.Completed, task.status)
        assertEquals(BooleanValue(true, bindings.snapshotCodec.symbolTable()), task.localVariables.getValue("flag"))
    }

    @Test
    fun immediateFunctionDefinitionReturnsWithoutSuspension() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            immediateFunction("append", listOf(CustomFunctionParameter("text", "String")), execute = { arguments, _ ->
                events += (arguments.single() as StringValue).value

                NullValue
            })
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    append("one")
                    append("two")
                """.trimIndent(),
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
            executionEnvironment = bindings.executionEnvironment,
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
            registerHostType(BindingNarrativeObject::class, "binding_obj")
            global("obj", BindingNarrativeObject("Test"))
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = "\"${'$'}obj\"",
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
            registerHostType(BindingActor::class, "binding_actor")
            suspendableFunction(
                receiverType = "binding_actor",
                name = "awaitReady",
                onDispatch = { receiver, _, resume ->
                    ((receiver[0] as NarrativeHostValue).value as BindingActor).ready = true
                    resume(PromptFlagResponse(true))
                },
                onResume = { receiver, response, ctx ->
                    BooleanValue(
                        ((receiver[0] as NarrativeHostValue).value as BindingActor).ready && (response as PromptFlagResponse).enabled,
                        ctx.symbolTable
                    )
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
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(TaskStatus.Completed, task.status)
        assertEquals(BooleanValue(true, bindings.snapshotCodec.symbolTable()), task.localVariables.getValue("ok"))
    }

    @Test
    fun typedDslResolvesImmediateFunctionOverloadsByValueArgumentType() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            immediateFunction(
                name = "describe",
                valueParameters = listOf(CustomFunctionParameter("value", "Int")),
                execute = { arguments, _ ->
                    events += "int:${(arguments.single() as IntValue).value}"
                    UnitValue
                },
            )
            immediateFunction(
                name = "describe",
                valueParameters = listOf(CustomFunctionParameter("value", "String")),
                execute = { arguments, _ ->
                    events += "text:${(arguments.single() as StringValue).value}"
                    UnitValue
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
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
        val bindings = NarrativeBindings {
            registerHostType(BindingActor::class, "binding_actor")
            registerHostType(BindingTestNpcRef::class, "binding_npc")
            immediateMemberFunction("binding_actor", "label") { receiver, _ ->
                events += "actor:${((receiver[0] as NarrativeHostValue).value as BindingActor).ready}"
                UnitValue
            }
            immediateMemberFunction("binding_npc", "label") { receiver, _ ->
                events += "npc:${((receiver[0] as NarrativeHostValue).value as BindingTestNpcRef).id}"
                UnitValue
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
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
        val bindings = NarrativeBindings {
            registerHostType(BindingActor::class, "binding_actor")
            global("actor", actor)
            registerKotliteGlobalProperty(
                GlobalProperty(
                    SourcePosition.NONE,
                    "chapter",
                    "Int",
                    true,
                    { IntValue(chapter, it.symbolTable()) },
                    { it, v ->
                        chapter = (v as IntValue).value
                    })
            )
            registerKotliteExtensionProperty(
                ExtensionProperty(
                    declaredName = "ready",
                    receiver = "binding_actor",
                    type = "Boolean",
                    getter = { interpreter, receiver, _ ->
                        BooleanValue(
                            ((receiver as NarrativeHostValue).value as BindingActor).ready,
                            interpreter.symbolTable(),
                        )
                    },
                    setter = { _, receiver, value, _ ->
                        ((receiver as NarrativeHostValue).value as BindingActor).ready =
                            (value as BooleanValue).value
                    },
                )
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
                bindings,
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
        assertEquals(IntValue(2, bindings.snapshotCodec.symbolTable()), locals.getValue("observedChapter"))
        assertEquals(BooleanValue(true, bindings.snapshotCodec.symbolTable()), locals.getValue("observedReady"))
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
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
            immediateFunction("capture", listOf(CustomFunctionParameter("value", "Any"))) { arguments, context ->
                val value = when (val argument = arguments.single()) {
                    is NarrativeHostValue -> argument.value as RuntimeValue
                    else -> argument
                }
                val holder = assertIs<KotlinValueHolder<*>>(value)
                val elements =
                    assertIs<List<*>>(holder.value).map { assertIs<RuntimeValue>(it).convertToString() }
                events += elements.joinToString(prefix = "[", postfix = "]")
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    capture(listOf(1, 2, 3, 4, 5))
                """.trimIndent(),
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
            immediateFunction("capture", listOf(CustomFunctionParameter("value", "Any"))) { arguments, context ->
                events += when (val value = arguments.single()) {
                    is NullValue -> "null"
                    is DefaultArgumentMarker -> "<default>"
                    is BooleanValue -> value.value.toString()
                    is IntValue -> value.value.toString()
                    is LongValue -> value.value.toString()
                    is StringValue -> value.value
                    is NarrativeLambdaValue -> "Lambda(${value.lambdaId})"
                    is NarrativeEnumValue -> value.entryName
                    is NarrativeEnumEntriesValue -> value.entries.joinToString(
                        prefix = "[",
                        postfix = "]"
                    ) { it.entryName }

                    is NarrativeHostValue -> (value.value as RuntimeValue).convertToString()
                    else -> error("Unsupported type $value")
                }
                UnitValue
            }
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
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
            immediateFunction("capture", listOf(CustomFunctionParameter("value", "Any"))) { arguments, context ->
                events += when (val value = arguments.single()) {
                    is NullValue -> "null"
                    is DefaultArgumentMarker -> "<default>"
                    is BooleanValue -> value.value.toString()
                    is IntValue -> value.value.toString()
                    is LongValue -> value.value.toString()
                    is StringValue -> value.value
                    is NarrativeLambdaValue -> "Lambda(${value.lambdaId})"
                    is NarrativeEnumValue -> value.entryName
                    is NarrativeEnumEntriesValue -> value.entries.joinToString(
                        prefix = "[",
                        postfix = "]"
                    ) { it.entryName }

                    is NarrativeHostValue -> (value.value as RuntimeValue).convertToString()
                    else -> error("Unsupported type $value")
                }
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val map = mapOf("hello" to 5)
                    capture(map["hello"])
                """.trimIndent(),
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val restored = bindings.snapshotCodec.restore(instance.serializeState())
        val locals = restored.tasks.single().localVariables
        val list = locals.getValue("list") as RuntimeValue
        val map = locals.getValue("map") as RuntimeValue

        assertEquals("List", list.type().name)
        assertEquals("Map", map.type().name)
        assertEquals(
            "5",
            ((map as KotlinValueHolder<*>).value as Map<*, *>).values.single()
                .let { (it as RuntimeValue).convertToString() })
    }

    @Test
    fun mutableCollectionsSupportIndexAssignmentInNarrative() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            immediateFunction("capture", listOf(CustomFunctionParameter("value", "Any"))) { arguments, context ->
                events += when (val value = arguments.single()) {
                    is NullValue -> "null"
                    is DefaultArgumentMarker -> "<default>"
                    is BooleanValue -> value.value.toString()
                    is IntValue -> value.value.toString()
                    is LongValue -> value.value.toString()
                    is StringValue -> value.value
                    is NarrativeLambdaValue -> "Lambda(${value.lambdaId})"
                    is NarrativeEnumValue -> value.entryName
                    is NarrativeEnumEntriesValue -> value.entries.joinToString(
                        prefix = "[",
                        postfix = "]"
                    ) { it.entryName }

                    is NarrativeHostValue -> (value.value as RuntimeValue).convertToString()
                    else -> error("Unsupported type $value")
                }
                UnitValue
            }
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
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
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
                        "${'$'}this: ${'$'}index (${'$'}value)"
                        return this
                    }
                    "Так, ну допустим"["И вот это"] = 1
                """.trimIndent().replace("${'$'}", "$"),
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Так, ну допустим: И вот это (1)"), events)
    }

    @Test
    fun inheritanceWithNarrativeTypes() = runTest {
        val events = mutableListOf<String>()
        val host = object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
            override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
        }

        open class A(val out: String) {
            fun methodA() {
                host.narrate("A::$out") {}
            }
        }

        class B(name: String) : A(name) {
            fun methodB() {
                host.narrate("B::$out") {}
            }
        }

        val bindings = NarrativeBindings {
            register(NarrativeBuiltinFunctions.definitions(host))
            registerHostType(A::class, "type_a")
            registerHostType(B::class, "type_b", superTypeIds = listOf("type_a"))
            immediateFunction("A", listOf(CustomFunctionParameter("name", "String")), returnType = "type_a") { args, ctx ->
                NarrativeHostValue("type_a", A((args[0] as StringValue).value), ctx.symbolTable)
            }
            immediateFunction("B", listOf(CustomFunctionParameter("name", "String")), returnType = "type_b") { args, ctx ->
                NarrativeHostValue("type_b", B((args[0] as StringValue).value), ctx.symbolTable)
            }

            immediateFunction("methodB", receiverType = "type_b") { args, _ ->
                ((args[0] as NarrativeHostValue).value as B).methodB()
                UnitValue
            }
            immediateFunction("methodA", receiverType = "type_a") { args, _ ->
                ((args[0] as NarrativeHostValue).value as A).methodA()
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val b = B("Test")
                    b.methodB()
                    b.methodA()
                """.trimIndent(),
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("B::Test", "A::Test"), events)
    }

    @Test
    fun registeredFunctionsSupportNamedAndDefaultArguments() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            importExecutionEnvironmentFunctions(false)
            immediateFunction(
                name = "format",
                valueParameters = listOf(
                    CustomFunctionParameter("prefix", "String", "\"default\""),
                    CustomFunctionParameter("suffix", "String"),
                ),
                returnType = "String",
            ) { args, ctx ->
                StringValue("${(args[0] as StringValue).value}:${(args[1] as StringValue).value}", ctx.symbolTable)
            }
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "String")),
            ) { args, _ ->
                events += (args.single() as StringValue).value
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    capture(format(suffix = "named"))
                    capture(format("explicit", suffix = "mixed"))
                """.trimIndent(),
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("default:named", "explicit:mixed"), events)
    }

    @Test
    fun userFunctionsSupportNamedAndDefaultArguments() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            registerBuiltinFunctions(object : NarrativeHost {
                override fun narrate(text: String, resume: () -> Unit) {
                    events += text
                    resume()
                }

                override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) = error("unused")
                override fun readLine(question: String, resume: (String) -> Unit) = error("unused")
            })
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    fun combine(prefix: String = "default", suffix: String = "value"): String {
                        return "${'$'}prefix:${'$'}suffix"
                    }

                    narrate(combine(suffix = "named"))
                    narrate(combine("explicit", suffix = "mixed"))
                """.trimIndent(),
                bindings
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("default:named", "explicit:mixed"), events)
    }

    @Test
    fun executionEnvironmentFunctionsSupportNamedAndDefaultArguments() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            registerKotliteFunction(
                CustomFunctionDefinition(
                    position = SourcePosition.NONE,
                    receiverType = null,
                    functionName = "bridgeFormat",
                    returnType = "String",
                    parameterTypes = listOf(
                        CustomFunctionParameter("prefix", "String", "\"default\""),
                        CustomFunctionParameter("suffix", "String"),
                    ),
                    executable = { interpreter, _, args, _ ->
                        StringValue(
                            value = "${(args[0] as StringValue).value}:${(args[1] as StringValue).value}",
                            symbolTable = interpreter.symbolTable(),
                        )
                    },
                )
            )
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "String")),
            ) { args, _ ->
                events += (args.single() as StringValue).value
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    capture(bridgeFormat(suffix = "named"))
                    capture(bridgeFormat("explicit", suffix = "mixed"))
                """.trimIndent(),
                bindings,
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("default:named", "explicit:mixed"), events)
    }

    @Test
    fun katariCompilerSupportsNativeEnumEntriesValueOfAndProperties() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "Any")),
            ) { args, _ ->
                val value = args.single()
                events += when (value) {
                    is NarrativeEnumValue -> value.entryName
                    is StringValue -> value.value
                    is IntValue -> value.value.toString()
                    else -> value.toString()
                }
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    enum class Mood(val label: String, val score: Int = 2) {
                        Happy("Happy", 5),
                        Sad(label = "Sad"),
                    }

                    fun describe(mood: Mood): String {
                        return "${'$'}{mood.label}:${'$'}{mood.score}"
                    }

                    capture(Mood.Happy)
                    capture(Mood.valueOf("Sad"))
                    capture(Mood.Happy.label)
                    capture(Mood.valueOf("Sad").score)
                    capture(describe(Mood.Happy))
                    var total = 0
                    for (mood in Mood.entries) {
                        total += mood.score
                    }
                    capture(total)
                """.trimIndent(),
                bindings = bindings,
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Happy", "Sad", "Happy", "2", "Happy:5", "7"), events)
    }

    @Test
    fun katariBindingsCanImportRegisteredEnumDefinitions() = runTest {
        val events = mutableListOf<String>()
        val bindings = NarrativeBindings {
            registerEnum(BindingMood::class, "BindingMood", BindingMood.entries)
            global("initialMood", BindingMood.Angry)
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "BindingMood")),
            ) { args, _ ->
                events += (args.single() as NarrativeEnumValue).entryName
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    capture(BindingMood.Calm)
                    capture(BindingMood.valueOf("Angry"))
                    capture(initialMood)
                """.trimIndent(),
                bindings = bindings,
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            snapshotCodec = bindings.snapshotCodec,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Calm", "Angry", "Angry"), events)
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

enum class BindingMood {
    Calm,
    Angry,
}


