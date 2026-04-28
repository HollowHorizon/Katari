import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.LongValue
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.stdlib.CollectionsLibModule
import com.sunnychung.lib.multiplatform.kotlite.stdlib.RandomLibModule
import com.sunnychung.lib.multiplatform.kotlite.stdlib.RangeLibModule
import kotlin.test.Test
import kotlin.test.assertTrue

class RandomLibTest {

    @Test
    fun randomObjectSupportsPrimitiveGeneration() {
        val env = ExecutionEnvironment().apply {
            install(RandomLibModule())
        }
        val interpreter = interpreter(
            """
                val a = Random.nextBoolean()
                val b = Random.nextInt(10, 20)
                val c = Random.nextLong(100L, 200L)
                val d = Random.nextDouble(1.5, 2.5)
            """.trimIndent(),
            executionEnvironment = env,
        )
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertTrue((symbolTable.findPropertyByDeclaredName("a") as BooleanValue).value is Boolean)
        assertTrue((symbolTable.findPropertyByDeclaredName("b") as IntValue).value in 10 until 20)
        assertTrue((symbolTable.findPropertyByDeclaredName("c") as LongValue).value in 100L until 200L)
        compareNumberInRange(1.5, 2.5, symbolTable.findPropertyByDeclaredName("d") as DoubleValue)
    }

    @Test
    fun rangesAndCollectionsCanUseDefaultRandomArgument() {
        val env = ExecutionEnvironment().apply {
            install(CollectionsLibModule())
            install(RangeLibModule())
            install(RandomLibModule())
        }
        val interpreter = interpreter(
            """
                val a = (3..7).random()
                val b = (100L..120L).random()
                val c = listOf("red", "green", "blue").random()
            """.trimIndent(),
            executionEnvironment = env,
        )
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertTrue((symbolTable.findPropertyByDeclaredName("a") as IntValue).value in 3..7)
        assertTrue((symbolTable.findPropertyByDeclaredName("b") as LongValue).value in 100L..120L)
        assertTrue((symbolTable.findPropertyByDeclaredName("c") as StringValue).value in setOf("red", "green", "blue"))
    }

    @Test
    fun namedRandomArgumentWorks() {
        val env = ExecutionEnvironment().apply {
            install(CollectionsLibModule())
            install(RangeLibModule())
            install(RandomLibModule())
        }
        val interpreter = interpreter(
            """
                val a = (10..20).random(random = Random)
                val b = listOf(1, 2, 3).random(random = Random)
            """.trimIndent(),
            executionEnvironment = env,
        )
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertTrue((symbolTable.findPropertyByDeclaredName("a") as IntValue).value in 10..20)
        assertTrue((symbolTable.findPropertyByDeclaredName("b") as IntValue).value in 1..3)
    }

    private fun compareNumberInRange(fromInclusive: Double, untilExclusive: Double, actual: DoubleValue) {
        assertTrue(actual.value >= fromInclusive)
        assertTrue(actual.value < untilExclusive)
    }
}
