import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.NumberValue
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules
import com.sunnychung.lib.multiplatform.kotlite.stdlib.CollectionsLibModule
import com.sunnychung.lib.multiplatform.kotlite.stdlib.IOLibModule
import com.sunnychung.lib.multiplatform.kotlite.stdlib.RangeLibModule
import kotlin.test.Test
import kotlin.test.assertEquals

class RangeTest {

    @Test
    fun customClassClosedRangeContainsOperator() {
        val env = ExecutionEnvironment().apply {
            install(RangeLibModule())
        }
        val interpreter = interpreter("""
            class A(val x: Int) : Comparable<A> {
                override operator fun compareTo(o: A): Int {
                    return x.compareTo(o.x)
                }
            }
            val r = A(3)..A(15)
            val typeCheck: Boolean = r is ClosedRange<A>
            val a: Boolean = A(2) in r
            val b: Boolean = A(3) in r
            val c: Boolean = A(4) in r
            val d: Boolean = A(12) in r
            val e: Boolean = A(14) in r
            val f: Boolean = A(15) in r
            val g: Boolean = A(16) in r
            val h: Boolean = A(500) in r
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("typeCheck") as BooleanValue).value)
        assertEquals(false, (symbolTable.findPropertyByDeclaredName("a") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("b") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("c") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("d") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("e") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("f") as BooleanValue).value)
        assertEquals(false, (symbolTable.findPropertyByDeclaredName("g") as BooleanValue).value)
        assertEquals(false, (symbolTable.findPropertyByDeclaredName("h") as BooleanValue).value)
    }

    @Test
    fun customClassOpenEndRangeContainsOperator() {
        val env = ExecutionEnvironment().apply {
            install(RangeLibModule())
        }
        val interpreter = interpreter("""
            class A(val x: Int) : Comparable<A> {
                override operator fun compareTo(o: A): Int {
                    return x.compareTo(o.x)
                }
            }
            val r = A(3)..<A(15)
            val typeCheck: Boolean = r is OpenEndRange<A>
            val a: Boolean = A(2) in r
            val b: Boolean = A(3) in r
            val c: Boolean = A(4) in r
            val d: Boolean = A(12) in r
            val e: Boolean = A(14) in r
            val f: Boolean = A(15) in r
            val g: Boolean = A(16) in r
            val h: Boolean = A(500) in r
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("typeCheck") as BooleanValue).value)
        assertEquals(false, (symbolTable.findPropertyByDeclaredName("a") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("b") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("c") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("d") as BooleanValue).value)
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("e") as BooleanValue).value)
        assertEquals(false, (symbolTable.findPropertyByDeclaredName("f") as BooleanValue).value)
        assertEquals(false, (symbolTable.findPropertyByDeclaredName("g") as BooleanValue).value)
        assertEquals(false, (symbolTable.findPropertyByDeclaredName("h") as BooleanValue).value)
    }

    @Test
    fun customClassClosedRangeStartEndInclusiveProperty() {
        val env = ExecutionEnvironment().apply {
            install(RangeLibModule())
        }
        val interpreter = interpreter("""
            class A(val x: Int) : Comparable<A> {
                override operator fun compareTo(o: A): Int {
                    return x.compareTo(o.x)
                }
            }
            val r = A(3)..A(15)
            val typeCheck: Boolean = r is ClosedRange<A>
            val a: Int = r.start.x
            val b: Int = r.endInclusive.x
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("typeCheck") as BooleanValue).value)
        assertEquals(3, (symbolTable.findPropertyByDeclaredName("a") as IntValue).value)
        assertEquals(15, (symbolTable.findPropertyByDeclaredName("b") as IntValue).value)
    }

    @Test
    fun customClassOpenEndRangeStartEndInclusiveProperty() {
        val env = ExecutionEnvironment().apply {
            install(RangeLibModule())
        }
        val interpreter = interpreter("""
            class A(val x: Int) : Comparable<A> {
                override operator fun compareTo(o: A): Int {
                    return x.compareTo(o.x)
                }
            }
            val r = A(3)..<A(15)
            val typeCheck: Boolean = r is OpenEndRange<A>
            val a: Int = r.start.x
            val b: Int = r.endExclusive.x
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertEquals(true, (symbolTable.findPropertyByDeclaredName("typeCheck") as BooleanValue).value)
        assertEquals(3, (symbolTable.findPropertyByDeclaredName("a") as IntValue).value)
        assertEquals(15, (symbolTable.findPropertyByDeclaredName("b") as IntValue).value)
    }

    @Test
    fun customClassComparableCoerceFunctions() {
        val env = ExecutionEnvironment().apply {
            install(RangeLibModule())
        }
        val interpreter = interpreter("""
            class A(val x: Int) : Comparable<A> {
                override operator fun compareTo(o: A): Int {
                    return x.compareTo(o.x)
                }
            }
            val low = A(3)
            val high = A(15)
            val inside = A(9)
            val a: Int = A(2).coerceAtLeast(low).x
            val b: Int = A(20).coerceAtMost(high).x
            val c: Int = A(2).coerceIn(low, high).x
            val d: Int = A(20).coerceIn(low..high).x
            val e: Int = inside.coerceIn(low, high).x
            val f: Int = inside.coerceIn(null, high).x
            val g: Int = inside.coerceIn(low, null).x
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertEquals(3, (symbolTable.findPropertyByDeclaredName("a") as IntValue).value)
        assertEquals(15, (symbolTable.findPropertyByDeclaredName("b") as IntValue).value)
        assertEquals(3, (symbolTable.findPropertyByDeclaredName("c") as IntValue).value)
        assertEquals(15, (symbolTable.findPropertyByDeclaredName("d") as IntValue).value)
        assertEquals(9, (symbolTable.findPropertyByDeclaredName("e") as IntValue).value)
        assertEquals(9, (symbolTable.findPropertyByDeclaredName("f") as IntValue).value)
        assertEquals(9, (symbolTable.findPropertyByDeclaredName("g") as IntValue).value)
    }

    @Test
    fun intLongClosedRangeContainsOperatorFirstLastProperty() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val cast: Int.() -> Number = { if (type == "Long") toLong() else this }
            val env = ExecutionEnvironment().apply {
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                val r = 3$l..15$l
                val typeCheck: Boolean = r is ${type}Range
                val a: Boolean = 2$l in r
                val b: Boolean = 3$l in r
                val c: Boolean = 4$l in r
                val d: Boolean = 12$l in r
                val e: Boolean = 14$l in r
                val f: Boolean = 15$l in r
                val g: Boolean = 16$l in r
                val h: Boolean = 500$l in r
                val start: $type = r.first
                val end: $type = r.last
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            val symbolTable = interpreter.symbolTable()
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("typeCheck") as BooleanValue).value)
            assertEquals(false, (symbolTable.findPropertyByDeclaredName("a") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("b") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("c") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("d") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("e") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("f") as BooleanValue).value)
            assertEquals(false, (symbolTable.findPropertyByDeclaredName("g") as BooleanValue).value)
            assertEquals(false, (symbolTable.findPropertyByDeclaredName("h") as BooleanValue).value)
            assertEquals(3.cast(), (symbolTable.findPropertyByDeclaredName("start") as NumberValue<*>).value)
            assertEquals(15.cast(), (symbolTable.findPropertyByDeclaredName("end") as NumberValue<*>).value)
        }
    }

    @Test
    fun intLongOpenEndRangeContainsOperatorFirstLastProperty() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val cast: Int.() -> Number = { if (type == "Long") toLong() else this }
            val env = ExecutionEnvironment().apply {
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                val r = 3$l..<15$l
                val typeCheck: Boolean = r is ${type}Range
                val a: Boolean = 2$l in r
                val b: Boolean = 3$l in r
                val c: Boolean = 4$l in r
                val d: Boolean = 12$l in r
                val e: Boolean = 14$l in r
                val f: Boolean = 15$l in r
                val g: Boolean = 16$l in r
                val h: Boolean = 500$l in r
                val start: $type = r.first
                val end: $type = r.last
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            val symbolTable = interpreter.symbolTable()
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("typeCheck") as BooleanValue).value)
            assertEquals(false, (symbolTable.findPropertyByDeclaredName("a") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("b") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("c") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("d") as BooleanValue).value)
            assertEquals(true, (symbolTable.findPropertyByDeclaredName("e") as BooleanValue).value)
            assertEquals(false, (symbolTable.findPropertyByDeclaredName("f") as BooleanValue).value)
            assertEquals(false, (symbolTable.findPropertyByDeclaredName("g") as BooleanValue).value)
            assertEquals(false, (symbolTable.findPropertyByDeclaredName("h") as BooleanValue).value)
            assertEquals(3.cast(), (symbolTable.findPropertyByDeclaredName("start") as NumberValue<*>).value)
            assertEquals(14.cast(), (symbolTable.findPropertyByDeclaredName("end") as NumberValue<*>).value)
        }
    }

    @Test
    fun intLongCoerceFunctions() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val cast: Int.() -> Number = { if (type == "Long") toLong() else this }
            val env = ExecutionEnvironment().apply {
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                val a: $type = 2$l.coerceAtLeast(3$l)
                val b: $type = 20$l.coerceAtMost(15$l)
                val c: $type = 2$l.coerceIn(3$l, 15$l)
                val d: $type = 20$l.coerceIn(3$l..15$l)
                val e: $type = 9$l.coerceIn(3$l, 15$l)
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            val symbolTable = interpreter.symbolTable()
            assertEquals(3.cast(), (symbolTable.findPropertyByDeclaredName("a") as NumberValue<*>).value)
            assertEquals(15.cast(), (symbolTable.findPropertyByDeclaredName("b") as NumberValue<*>).value)
            assertEquals(3.cast(), (symbolTable.findPropertyByDeclaredName("c") as NumberValue<*>).value)
            assertEquals(15.cast(), (symbolTable.findPropertyByDeclaredName("d") as NumberValue<*>).value)
            assertEquals(9.cast(), (symbolTable.findPropertyByDeclaredName("e") as NumberValue<*>).value)
        }
    }

    @Test
    fun byteDoubleCoerceFunctions() {
        val env = ExecutionEnvironment().apply {
            install(AllStdLibModules())
        }
        val interpreter = interpreter("""
            val a: Byte = 2.toByte().coerceAtLeast(3.toByte())
            val b: Byte = 20.toByte().coerceAtMost(15.toByte())
            val c: Byte = 2.toByte().coerceIn(3.toByte(), 15.toByte())
            val d: Double = 2.0.coerceAtLeast(3.5)
            val e: Double = 20.0.coerceAtMost(15.5)
            val f: Double = 2.0.coerceIn(3.5, 15.5)
            val g: Double = 9.0.coerceIn(3.5, 15.5)
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        val symbolTable = interpreter.symbolTable()
        assertEquals(3.toByte(), (symbolTable.findPropertyByDeclaredName("a") as NumberValue<*>).value)
        assertEquals(15.toByte(), (symbolTable.findPropertyByDeclaredName("b") as NumberValue<*>).value)
        assertEquals(3.toByte(), (symbolTable.findPropertyByDeclaredName("c") as NumberValue<*>).value)
        assertEquals(3.5, (symbolTable.findPropertyByDeclaredName("d") as NumberValue<*>).value)
        assertEquals(15.5, (symbolTable.findPropertyByDeclaredName("e") as NumberValue<*>).value)
        assertEquals(3.5, (symbolTable.findPropertyByDeclaredName("f") as NumberValue<*>).value)
        assertEquals(9.0, (symbolTable.findPropertyByDeclaredName("g") as NumberValue<*>).value)
    }

    @Test
    fun intLongClosedRangeForLoop() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val console = StringBuilder()
            val env = ExecutionEnvironment().apply {
                install(object : IOLibModule() {
                    override fun outputToConsole(output: String) {
                        console.append(output)
                    }
                })
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                for (e in 3$l..15$l) {
                    println(e)
                    println(e * 2)
                }
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            assertEquals((3..15).joinToString("") { "$it\n${it * 2}\n" }, console.toString())
        }
    }

    @Test
    fun intLongClosedRangeForEach() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val console = StringBuilder()
            val env = ExecutionEnvironment().apply {
                install(object : IOLibModule() {
                    override fun outputToConsole(output: String) {
                        console.append(output)
                    }
                })
                install(CollectionsLibModule())
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                (3$l..15$l).forEach { e ->
                    println(e)
                    println(e * 2$l)
                }
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            assertEquals((3..15).joinToString("") { "$it\n${it * 2}\n" }, console.toString())
        }
    }

    @Test
    fun intLongOpenEndRangeForLoop() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val console = StringBuilder()
            val env = ExecutionEnvironment().apply {
                install(object : IOLibModule() {
                    override fun outputToConsole(output: String) {
                        console.append(output)
                    }
                })
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                for (e in 3$l..<15$l) {
                    println(e)
                    println(e * 2)
                }
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            assertEquals((3..<15).joinToString("") { "$it\n${it * 2}\n" }, console.toString())
        }
    }

    @Test
    fun intLongOpenEndRangeForEach() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val console = StringBuilder()
            val env = ExecutionEnvironment().apply {
                install(object : IOLibModule() {
                    override fun outputToConsole(output: String) {
                        console.append(output)
                    }
                })
                install(CollectionsLibModule())
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                (3$l..<15$l).forEach { e ->
                    println(e)
                    println(e * 2$l)
                }
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            assertEquals((3..<15).joinToString("") { "$it\n${it * 2}\n" }, console.toString())
        }
    }

    @Test
    fun intLongProgressionForLoopDownToStep() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val console = StringBuilder()
            val env = ExecutionEnvironment().apply {
                install(object : IOLibModule() {
                    override fun outputToConsole(output: String) {
                        console.append(output)
                    }
                })
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                for (e in 12$l downTo -6$l step 3$l) {
                    println(e)
                    println(e + 1)
                }
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            assertEquals("12\n13\n9\n10\n6\n7\n3\n4\n0\n1\n-3\n-2\n-6\n-5\n", console.toString())
        }
    }

    @Test
    fun intLongProgressionForLoopUntil() {
        listOf("Int", "Long").forEach { type ->
            val l = if (type == "Long") "L" else ""
            val console = StringBuilder()
            val env = ExecutionEnvironment().apply {
                install(object : IOLibModule() {
                    override fun outputToConsole(output: String) {
                        console.append(output)
                    }
                })
                install(RangeLibModule())
            }
            val interpreter = interpreter("""
                for (e in 12$l until 16$l) {
                    println(e)
                    println(e * 2$l)
                }
            """.trimIndent(), executionEnvironment = env, isDebug = true)
            interpreter.eval()
            assertEquals("12\n24\n13\n26\n14\n28\n15\n30\n", console.toString())
        }
    }

    @Test
    fun intRangeJoinToString() {
        val console = StringBuilder()
        val env = ExecutionEnvironment().apply {
            install(object : IOLibModule() {
                override fun outputToConsole(output: String) {
                    console.append(output)
                }
            })
            install(CollectionsLibModule())
            install(RangeLibModule())
        }
        val interpreter = interpreter("""
            println("${'$'}{(1..5).joinToString(" + ")} = ${'$'}{
                (1..5).fold(0) { acc, it ->
                    acc + it
                }
            }")
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        assertEquals("1 + 2 + 3 + 4 + 5 = 15\n", console.toString())
    }

    @Test
    fun longRangeJoinToString() {
        val console = StringBuilder()
        val env = ExecutionEnvironment().apply {
            install(object : IOLibModule() {
                override fun outputToConsole(output: String) {
                    console.append(output)
                }
            })
            install(CollectionsLibModule())
            install(RangeLibModule())
        }
        val interpreter = interpreter("""
            println("${'$'}{(1L..5L).joinToString(" + ")} = ${'$'}{
                (1L..5L).fold(0L) { acc, it ->
                    acc + it
                }
            }")
        """.trimIndent(), executionEnvironment = env, isDebug = true)
        interpreter.eval()
        assertEquals("1 + 2 + 3 + 4 + 5 = 15\n", console.toString())
    }
}
