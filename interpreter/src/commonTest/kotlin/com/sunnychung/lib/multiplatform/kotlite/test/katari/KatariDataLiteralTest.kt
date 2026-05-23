package com.sunnychung.lib.multiplatform.kotlite.test.katari

import com.sunnychung.lib.multiplatform.kotlite.katari.KatariInstance
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariNarrativeProgram
import com.sunnychung.lib.multiplatform.kotlite.katari.KatariState
import com.sunnychung.lib.multiplatform.kotlite.katari.NarrativeBindings
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskState
import com.sunnychung.lib.multiplatform.kotlite.katari.TaskStatus
import com.sunnychung.lib.multiplatform.kotlite.katari.ValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.katari.XmlValueSnapshot
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExtensionProperty
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeHostValue
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.model.StructArrayValue
import com.sunnychung.lib.multiplatform.kotlite.model.StructValue
import com.sunnychung.lib.multiplatform.kotlite.model.UnitValue
import com.sunnychung.lib.multiplatform.kotlite.model.XmlValue
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
class KatariDataLiteralTest {
    @Test
    fun xmlLiteralSupportsAttributesChildrenAndForEachExpansion() = runTest {
        val captures = mutableListOf<RuntimeValue>()
        val bindings = NarrativeBindings {
            install(AllStdLibModules())
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "Any")),
            ) { args, _ ->
                captures += args.single()
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val words = listOf("Hello", "World")
                    val example = "Выход"
                    capture(
                        <box size="256px 196px" layout='row'>
                            <button text="Принять" />
                            <button text=example />
                            words.forEach { it ->
                                <card label=it />
                            }
                        </box>
                    )
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
        val root = assertIs<XmlValue>(captures.single())
        assertEquals("box", root.name)
        assertEquals("256px 196px", (root.attributes[0].value as StringValue).value)
        assertEquals("row", (root.attributes[1].value as StringValue).value)
        assertEquals(listOf("button", "button", "card", "card"), root.children.map { it.name })
        assertEquals("Выход", (root.children[1].attributes.single().value as StringValue).value)
        assertEquals(listOf("Hello", "World"), root.children.drop(2).map { (it.attributes.single().value as StringValue).value })
    }

    @Test
    fun structLiteralSupportsNestedObjectsArraysAndQuotedKeys() = runTest {
        val captures = mutableListOf<RuntimeValue>()
        val bindings = NarrativeBindings {
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "Any")),
            ) { args, _ ->
                captures += args.single()
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    capture(struct {
                        custom_name: {text:"Клинок судьбы", color:'gold', bold:true},
                        item_name: {text:"Острый меч", color:"aqua"},
                        lore: [
                            {text:"Легендарное оружие", color:"dark_purple", italic:true}
                        ],
                        enchantments: {
                            levels: {'minecraft:sharpness':5, 'minecraft:unbreaking':3}
                        },
                        unbreakable:{}
                    })
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

        val root = assertIs<StructValue>(captures.single())
        val customName = assertIs<StructValue>(root.fields.getValue("custom_name"))
        val lore = assertIs<StructArrayValue>(root.fields.getValue("lore"))
        val levels = assertIs<StructValue>(
            assertIs<StructValue>(root.fields.getValue("enchantments")).fields.getValue("levels")
        )

        assertEquals(TaskStatus.Completed, instance.currentState().tasks.single().status)
        assertEquals("Клинок судьбы", (customName.fields.getValue("text") as StringValue).value)
        assertEquals("gold", (customName.fields.getValue("color") as StringValue).value)
        assertEquals(true, (customName.fields.getValue("bold") as BooleanValue).value)
        assertEquals(1, lore.elements.size)
        assertEquals("Легендарное оружие", (assertIs<StructValue>(lore.elements.single()).fields.getValue("text") as StringValue).value)
        assertEquals(5, (levels.fields.getValue("minecraft:sharpness") as IntValue).value)
        assertEquals(3, (levels.fields.getValue("minecraft:unbreaking") as IntValue).value)
        assertEquals(emptyMap(), assertIs<StructValue>(root.fields.getValue("unbreakable")).fields)
    }

    @Test
    fun structLiteralSupportsVariablesAndNavigationExpressions() = runTest {
        val captures = mutableListOf<RuntimeValue>()
        val bindings = NarrativeBindings {
            registerHostType(StructLiteralEntity::class, "Entity")
            global("entity", StructLiteralEntity(name = "Alex", uuid = "0000-1111"))
            registerKotliteExtensionProperty(
                ExtensionProperty(
                    declaredName = "name",
                    receiver = "Entity",
                    type = "String",
                    getter = { interpreter, receiver, _ ->
                        StringValue(
                            ((receiver as NarrativeHostValue).value as StructLiteralEntity).name,
                            interpreter.symbolTable(),
                        )
                    },
                )
            )
            registerKotliteExtensionProperty(
                ExtensionProperty(
                    declaredName = "uuid",
                    receiver = "Entity",
                    type = "String",
                    getter = { interpreter, receiver, _ ->
                        StringValue(
                            ((receiver as NarrativeHostValue).value as StructLiteralEntity).uuid,
                            interpreter.symbolTable(),
                        )
                    },
                )
            )
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "Any")),
            ) { args, _ ->
                captures += args.single()
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val example = struct { x: 10, y: 15, z: 35 }
                    val data = struct {
                        npc: {
                            name: entity.name,
                            uuid: entity.uuid
                        },
                        pos: example
                    }
                    capture(data)
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
        val root = assertIs<StructValue>(captures.single())
        val npc = assertIs<StructValue>(root.fields.getValue("npc"))
        val pos = assertIs<StructValue>(root.fields.getValue("pos"))

        assertEquals("Alex", (npc.fields.getValue("name") as StringValue).value)
        assertEquals("0000-1111", (npc.fields.getValue("uuid") as StringValue).value)
        assertEquals(10, (pos.fields.getValue("x") as IntValue).value)
        assertEquals(15, (pos.fields.getValue("y") as IntValue).value)
        assertEquals(35, (pos.fields.getValue("z") as IntValue).value)
    }

    @Test
    fun structValueSupportsIndexOperatorAndTypedGetters() = runTest {
        val captures = mutableListOf<RuntimeValue>()
        val bindings = NarrativeBindings {
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "Any")),
            ) { args, _ ->
                captures += args.single()
                UnitValue
            }
        }
        val instance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = """
                    val nested = struct { ok: true }
                    val data = struct {
                        x: 10,
                        enabled: true,
                        ratio: 2.5,
                        label: "hello",
                        items: [1, 2],
                        nested: nested
                    }
                    capture(data["x"])
                    capture(data.getInt("x"))
                    capture(data.getBoolean("enabled"))
                    capture(data.getDouble("ratio"))
                    capture(data.getDouble("x"))
                    capture(data.getString("label"))
                    capture(data.getStruct("nested"))
                    capture(data.getArray("items"))
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
        assertEquals(10, assertIs<IntValue>(captures[0]).value)
        assertEquals(10, assertIs<IntValue>(captures[1]).value)
        assertEquals(true, assertIs<BooleanValue>(captures[2]).value)
        assertEquals(2.5, assertIs<DoubleValue>(captures[3]).value)
        assertEquals(10.0, assertIs<DoubleValue>(captures[4]).value)
        assertEquals("hello", assertIs<StringValue>(captures[5]).value)
        assertEquals(true, (assertIs<StructValue>(captures[6]).fields.getValue("ok") as BooleanValue).value)
        assertEquals(listOf(1, 2), assertIs<StructArrayValue>(captures[7]).elements.map { (it as IntValue).value })
    }

    @Test
    fun xmlSnapshotPreservesSharedMutableAttributeValues() = runTest {
        val initialCaptures = mutableListOf<String>()
        val initialBindings = valueTypeBindings(
            captures = initialCaptures,
            resumePause = false,
        )
        val code = """
            val data = ValueType("hello")
            val xml = <example tag=data />
            capture(xml)
            pause()
            data.value = "world"
            capture(xml)
        """.trimIndent()
        val initialInstance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = code,
                bindings = initialBindings,
            ),
            initialState = KatariState(
                programVersion = 1,
                tasks = listOf(TaskState(id = "main")),
                globals = initialBindings.globals,
            ),
            executionEnvironment = initialBindings.executionEnvironment,
            snapshotCodec = initialBindings.snapshotCodec,
            coroutineScope = this,
        )

        initialInstance.start()
        advanceUntilIdle()

        assertEquals(listOf("hello"), initialCaptures)
        val snapshot = initialInstance.serializeState()
        val rootFrame = snapshot.tasks.single().callFrames.first { it.functionId == "__main__" }
        val dataRef = rootFrame.variableRefs.getValue("data")
        val xmlRef = rootFrame.variableRefs.getValue("xml")
        val xmlSnapshot = assertIs<XmlValueSnapshot>(snapshot.values.getValue(xmlRef.valueId))
        assertEquals(dataRef, xmlSnapshot.attributes.single().valueRef)
        assertEquals(1, snapshot.values.values.filterIsInstance<XmlLiteralValueSnapshot>().size)
        initialInstance.cancel()

        val resumedCaptures = mutableListOf<String>()
        val resumedBindings = valueTypeBindings(
            captures = resumedCaptures,
            resumePause = true,
        )
        val resumedInstance = KatariInstance(
            program = KatariNarrativeProgram(
                filename = "<Narrative>",
                code = code,
                bindings = resumedBindings,
            ),
            initialState = resumedBindings.snapshotCodec.restore(snapshot),
            executionEnvironment = resumedBindings.executionEnvironment,
            snapshotCodec = resumedBindings.snapshotCodec,
            coroutineScope = this,
        )

        resumedInstance.start()
        advanceUntilIdle()
        resumedInstance.join()

        assertEquals(TaskStatus.Completed, resumedInstance.currentState().tasks.single().status)
        assertEquals(listOf("world"), resumedCaptures)
    }

    private fun valueTypeBindings(
        captures: MutableList<String>,
        resumePause: Boolean,
    ): KatariBindings {
        return NarrativeBindings {
            registerHostType(
                XmlLiteralValue::class,
                "ValueType",
                snapshotClass = XmlLiteralValueSnapshot::class,
                snapshotSerializer = XmlLiteralValueSnapshot.serializer(),
                serialize = { value -> XmlLiteralValueSnapshot(value.value) },
                deserialize = { snapshot, _ -> XmlLiteralValue(snapshot.value) },
            )
            immediateFunction(
                name = "ValueType",
                valueParameters = listOf(CustomFunctionParameter("value", "String")),
                returnType = "ValueType",
            ) { args, context ->
                NarrativeHostValue(
                    typeId = "ValueType",
                    value = XmlLiteralValue((args.single() as StringValue).value),
                    symbolTable = context.symbolTable,
                )
            }
            registerKotliteExtensionProperty(
                ExtensionProperty(
                    declaredName = "value",
                    receiver = "ValueType",
                    type = "String",
                    getter = { interpreter, receiver, _ ->
                        StringValue(
                            ((receiver as NarrativeHostValue).value as XmlLiteralValue).value,
                            interpreter.symbolTable(),
                        )
                    },
                    setter = { _, receiver, value, _ ->
                        ((receiver as NarrativeHostValue).value as XmlLiteralValue).value =
                            (value as StringValue).value
                    },
                )
            )
            immediateFunction(
                name = "capture",
                valueParameters = listOf(CustomFunctionParameter("value", "XmlValue")),
            ) { args, _ ->
                val xml = args.single() as XmlValue
                val tag = xml.attributes.single().value as NarrativeHostValue
                captures += (tag.value as XmlLiteralValue).value
                UnitValue
            }
            suspendableFunction(
                name = "pause",
                onDispatch = { _, _, resume ->
                    if (resumePause) {
                        resume(FunctionResponse.Ack)
                    }
                },
                onResume = { _, _, _ -> UnitValue },
            )
        }
    }
}

private data class XmlLiteralValue(var value: String)

private data class StructLiteralEntity(
    val name: String,
    val uuid: String,
)

@Serializable
@SerialName("xml_literal_value")
private data class XmlLiteralValueSnapshot(val value: String) : ValueSnapshot()
