val <T : Comparable<T>> ClosedRange<T>.start: T get()
val <T : Comparable<T>> ClosedRange<T>.endInclusive: T get()

operator fun <T : Comparable<T>> ClosedRange<T>.contains(value: T): Boolean
fun <T : Comparable<T>> ClosedRange<T>.isEmpty(): Boolean

val <T : Comparable<T>> OpenEndRange<T>.start: T get()
val <T : Comparable<T>> OpenEndRange<T>.endExclusive: T get()

operator fun <T : Comparable<T>> OpenEndRange<T>.contains(value: T): Boolean
fun <T : Comparable<T>> OpenEndRange<T>.isEmpty(): Boolean

operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>
operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>
fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T
fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T
fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T
fun Byte.coerceIn(minimumValue: Byte, maximumValue: Byte): Byte
fun Short.coerceIn(minimumValue: Short, maximumValue: Short): Short
fun Int.coerceIn(minimumValue: Int, maximumValue: Int): Int
fun Long.coerceIn(minimumValue: Long, maximumValue: Long): Long
fun Float.coerceIn(minimumValue: Float, maximumValue: Float): Float
fun Double.coerceIn(minimumValue: Double, maximumValue: Double): Double
fun Byte.coerceAtLeast(minimumValue: Byte): Byte
fun Short.coerceAtLeast(minimumValue: Short): Short
fun Int.coerceAtLeast(minimumValue: Int): Int
fun Long.coerceAtLeast(minimumValue: Long): Long
fun Float.coerceAtLeast(minimumValue: Float): Float
fun Double.coerceAtLeast(minimumValue: Double): Double
fun Byte.coerceAtMost(maximumValue: Byte): Byte
fun Short.coerceAtMost(maximumValue: Short): Short
fun Int.coerceAtMost(maximumValue: Int): Int
fun Long.coerceAtMost(maximumValue: Long): Long
fun Float.coerceAtMost(maximumValue: Float): Float
fun Double.coerceAtMost(maximumValue: Double): Double

//operator fun ClosedRange<Int>.contains(value: Byte): Boolean
//operator fun ClosedRange<Long>.contains(value: Byte): Boolean
//operator fun ClosedRange<Long>.contains(value: Int): Boolean
//operator fun ClosedRange<Byte>.contains(value: Int): Boolean
//operator fun ClosedRange<Int>.contains(value: Long): Boolean
//operator fun ClosedRange<Byte>.contains(value: Long): Boolean
//operator fun OpenEndRange<Int>.contains(value: Byte): Boolean
//operator fun OpenEndRange<Long>.contains(value: Byte): Boolean
//operator fun OpenEndRange<Long>.contains(value: Int): Boolean
//operator fun OpenEndRange<Byte>.contains(value: Int): Boolean
//operator fun OpenEndRange<Int>.contains(value: Long): Boolean
//operator fun OpenEndRange<Byte>.contains(value: Long): Boolean

val IntProgression.first: Int get()
val IntProgression.last: Int get()
val IntProgression.step: Int get()
fun IntProgression.isEmpty(): Boolean
fun IntProgression.reversed(): IntProgression
infix fun IntProgression.step(step: Int): IntProgression
operator fun IntRange.contains(element: Int?): Boolean
fun IntRange.random(): Int
fun IntRange.randomOrNull(): Int?
infix fun Int.downTo(to: Byte): IntProgression
infix fun Int.downTo(to: Int): IntProgression
infix fun Int.downTo(to: Long): LongProgression
infix fun Int.until(to: Byte): IntRange
infix fun Int.until(to: Int): IntRange
infix fun Int.until(to: Long): LongRange
operator fun Int.rangeTo(that: Int): IntRange
operator fun Int.rangeUntil(that: Int): IntRange

val LongProgression.first: Long get()
val LongProgression.last: Long get()
val LongProgression.step: Long get()
fun LongProgression.isEmpty(): Boolean
fun LongProgression.reversed(): LongProgression
infix fun LongProgression.step(step: Long): LongProgression
operator fun LongRange.contains(element: Long?): Boolean
fun LongRange.random(): Long
fun LongRange.randomOrNull(): Long?
infix fun Long.downTo(to: Byte): LongProgression
infix fun Long.downTo(to: Int): LongProgression
infix fun Long.downTo(to: Long): LongProgression
infix fun Long.until(to: Byte): LongRange
infix fun Long.until(to: Int): LongRange
infix fun Long.until(to: Long): LongRange
operator fun Long.rangeTo(that: Long): LongRange
operator fun Long.rangeUntil(that: Long): LongRange
