package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.model.UnitValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class KatariWhenTest {
    @Test
    fun whenExpressionSupportsSubjectAndBooleanBranches() = runTest {
        val captures = mutableListOf<String>()
        val bindings = captureBindings(captures)
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                val value = 2
                val subjectResult = when (value) {
                    1 -> "one"
                    2, 3 -> "few"
                    else -> "many"
                }
                val conditionResult = when {
                    value > 3 -> "large"
                    else -> "small"
                }
                capture(subjectResult)
                capture(conditionResult)
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
        assertEquals(listOf("few", "small"), captures)
    }

    @Test
    fun whenStatementSupportsSubjectValueDeclarationAndTypeTests() = runTest {
        val captures = mutableListOf<String>()
        val bindings = captureBindings(captures)
        val program = KatariNarrativeProgram(
            filename = "main.ktr",
            code = """
                val value: Any = "text"
                when (val subject = value) {
                    is String -> capture(subject)
                    else -> capture("other")
                }
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
        assertEquals(listOf("text"), captures)
    }

    private fun captureBindings(captures: MutableList<String>) = NarrativeBindings {
        immediateFunction(
            name = "capture",
            valueParameters = listOf(CustomFunctionParameter("value", "Any")),
        ) { args, _ ->
            captures += (args.single() as StringValue).value
            UnitValue
        }
    }
}
