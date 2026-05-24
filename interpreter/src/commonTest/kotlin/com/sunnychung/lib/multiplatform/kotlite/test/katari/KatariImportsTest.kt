package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.ChoiceOptionSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariSource
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariSourceProvider
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariSourceRequest
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBuiltinFunctions
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeHost
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import com.sunnychung.lib.multiplatform.kotlite.katari.analyzeKatariNarrativeScript
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.UnitValue
import com.sunnychung.lib.multiplatform.kotlite.katari.StateSnapshotCodec
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KatariImportsTest {

    private fun symbolTable() = StateSnapshotCodec().symbolTable()

    @Test
    fun importKatariScriptExecutesImportedTopLevelBeforeMain() = runTest {
        val events = mutableListOf<String>()
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                import katari 'library.ktr'
                greet("Main")
            """.trimIndent(),
            sourceProvider = mapSourceProvider(
                "library.ktr" to """
                    "Library top-level"
                    fun greet(name: String) {
                        "Hello, ${'$'}name"
                    }
                """.trimIndent(),
            ),
        )
        val instance = KatariInstance(
            program = program,
            executionEnvironment = NarrativeBuiltinFunctions.environment(recordingHost(events)),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Library top-level", "Hello, Main"), events)
    }

    @Test
    fun importKatariScriptFunctionCanUseImportedTopLevelVariables() = runTest {
        val events = mutableListOf<String>()
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                import katari "library.ktr" as library
                library.greet("Main")
            """.trimIndent(),
            sourceProvider = mapSourceProvider(
                "library.ktr" to """
                    val prefix = "Library"
                    fun greet(name: String) {
                        "${'$'}prefix ${'$'}name"
                    }
                """.trimIndent(),
            ),
        )
        val instance = KatariInstance(
            program = program,
            executionEnvironment = NarrativeBuiltinFunctions.environment(recordingHost(events)),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Library Main"), events)
    }

    @Test
    fun importKatariScriptAsNamespaceAvoidsFunctionNameCollisions() = runTest {
        val events = mutableListOf<String>()
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                import katari "library.ktr" as library
                fun greet(name: String) {
                    "Main ${'$'}name"
                }
                greet("Call")
                library.greet("Call")
            """.trimIndent(),
            sourceProvider = mapSourceProvider(
                "library.ktr" to """
                    fun greet(name: String) {
                        "Library ${'$'}name"
                    }
                """.trimIndent(),
            ),
        )
        val instance = KatariInstance(
            program = program,
            executionEnvironment = NarrativeBuiltinFunctions.environment(recordingHost(events)),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Main Call", "Library Call"), events)
    }

    @Test
    fun importKatariScriptAsNamespaceExecutesTopLevelWithNamespacedFunctions() = runTest {
        val events = mutableListOf<String>()
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                import katari "library.ktr" as library
                library.greet("Main")
            """.trimIndent(),
            sourceProvider = mapSourceProvider(
                "library.ktr" to """
                    fun greet(name: String) {
                        "Library ${'$'}name"
                    }
                    greet("Load")
                """.trimIndent(),
            ),
        )
        val instance = KatariInstance(
            program = program,
            executionEnvironment = NarrativeBuiltinFunctions.environment(recordingHost(events)),
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf("Library Load", "Library Main"), events)
    }

    @Test
    fun importKatariScriptAsNamespaceExposesTopLevelProperties() = runTest {
        val captures = mutableListOf<IntValue>()
        val bindings = NarrativeBindings {
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "Int")),
            ) { args, _ ->
                captures += args.single() as IntValue
                UnitValue
            }
        }
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                import katari "library.ktr" as library
                capture(library.answer)
            """.trimIndent(),
            bindings = bindings,
            sourceProvider = mapSourceProvider(
                "library.ktr" to """
                    val answer = 42
                """.trimIndent(),
            ),
        )
        val instance = KatariInstance(
            program = program,
            initialState = KatariState(programVersion = program.version, tasks = listOf(TaskState(id = "main"))),
            executionEnvironment = bindings.executionEnvironment,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals(listOf(42), captures.map { it.value })
    }

    @Test
    fun importKatariScriptRejectsCircularImports() {
        val error = assertFailsWith<IllegalArgumentException> {
            KatariNarrativeProgram(
                filename = "main.ktr",
                code = "import katari \"a.ktr\"",
                sourceProvider = mapSourceProvider(
                    "a.ktr" to "import katari \"b.ktr\"",
                    "b.ktr" to "import katari \"a.ktr\"",
                ),
            )
        }

        assertTrue(error.message?.contains("Circular Katari import") == true)
    }

    @Test
    fun preprocessorInstructionsAreExposedAndRemovedBeforeParsing() {
        val analysis = analyzeKatariNarrativeScript(
            filename = "main.ktr",
            code = """
                #script-version 2 // host can decide whether to restore state
                #loader server startup
                "Main"
            """.trimIndent(),
        )
        val instructions = analysis.preprocessorInstructions.getValue("main.ktr")

        assertEquals(listOf("script-version", "loader"), instructions.map { it.name })
        assertEquals(listOf("2"), instructions[0].arguments)
        assertEquals(listOf("server", "startup"), instructions[1].arguments)
    }

    @Test
    fun bindingImportAliasCanExposeImportedGlobals() = runTest {
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            import("kotlin.random.Random", alias = "R")
        }
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                val value = R.nextBoolean()
            """.trimIndent(),
            bindings = bindings,
        )
        val instance = KatariInstance(
            program = program,
            initialState = KatariState(
                programVersion = program.version,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
    }

    @Test
    fun bindingWildcardImportExposesQualifiedGlobalsBySimpleName() = runTest {
        val st = symbolTable()
        val bindings = NarrativeBindings {
            global("test.library.answer", IntValue(42, st))
            importWildcard("test.library")
        }
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                val result = answer
            """.trimIndent(),
            bindings = bindings,
        )
        val instance = KatariInstance(
            program = program,
            initialState = KatariState(
                programVersion = program.version,
                tasks = listOf(TaskState(id = "main")),
                globals = bindings.globals,
            ),
            executionEnvironment = bindings.executionEnvironment,
            coroutineScope = this,
        )

        instance.start()
        advanceUntilIdle()
        instance.join()

        val task = instance.currentState().tasks.single()
        assertEquals(TaskStatus.Completed, task.status)
        assertEquals(IntValue(42, st), task.localVariables.getValue("result"))
    }

    private fun mapSourceProvider(vararg sources: Pair<String, String>): KatariSourceProvider {
        val byPath = sources.toMap()
        return object : KatariSourceProvider {
            override fun readSource(request: KatariSourceRequest): KatariSource {
                val code = byPath[request.path] ?: error("Missing test source `${request.path}`")
                return KatariSource(filename = request.path, code = code, id = request.path)
            }
        }
    }

    private fun recordingHost(events: MutableList<String>): NarrativeHost {
        return object : NarrativeHost {
            override fun narrate(text: String, resume: () -> Unit) {
                events += text
                resume()
            }

            override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
                error("choose should not be called")
            }

            override fun readLine(question: String, resume: (String) -> Unit) {
                error("readLine should not be called")
            }
        }
    }
}
