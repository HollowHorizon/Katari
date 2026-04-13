package com.sunnychung.lib.multiplatform.kotlite.narrative

import kotlinx.serialization.modules.SerializersModule

class NarrativeStateSnapshotCodec(
    private val valueCodecs: NarrativeValueCodecRegistry = NarrativeValueCodecRegistry(emptyList()),
    functionRegistry: NarrativeFunctionRegistry = NarrativeBuiltinFunctions.registry(NarrativeNoOpHost),
) {

    fun serialize(state: NarrativeState): NarrativeStateSnapshot {
        return NarrativeStateSnapshot(
            programVersion = state.programVersion,
            globals = state.globals.mapValues { (_, value) -> serializeValue(value) },
            tasks = state.tasks.map { task ->
                NarrativeTaskSnapshot(
                    id = task.id,
                    instructionPointer = task.instructionPointer,
                    localVariables = task.localVariables.mapValues { (_, value) -> serializeValue(value) },
                    slots = task.slots.mapValues { (_, value) -> serializeSlot(value) },
                    status = serializeStatus(task.status),
                )
            },
        )
    }

    suspend fun restore(
        snapshot: NarrativeStateSnapshot,
        context: NarrativeValueRestoreContext = EmptyNarrativeValueRestoreContext,
    ): NarrativeState {
        return NarrativeState(
            programVersion = snapshot.programVersion,
            globals = snapshot.globals.mapValues { (_, value) -> restoreValue(value, context) },
            tasks = snapshot.tasks.map { task ->
                NarrativeTaskState(
                    id = task.id,
                    instructionPointer = task.instructionPointer,
                    localVariables = task.localVariables.mapValues { (_, value) -> restoreValue(value, context) },
                    slots = task.slots.mapValues { (_, value) -> restoreSlot(value, context) },
                    status = restoreStatus(task.status),
                )
            },
        )
    }

    fun serializersModule(): SerializersModule {
        return valueCodecs.serializersModule()
    }

    private fun serializeValue(value: NarrativeValue): NarrativeValueSnapshot {
        return when (value) {
            NarrativeValue.Null -> NullValueSnapshot
            is NarrativeValue.Bool -> BoolValueSnapshot(value.value)
            is NarrativeValue.Int32 -> Int32ValueSnapshot(value.value)
            is NarrativeValue.Text -> TextValueSnapshot(value.value)
            is NarrativeValue.Entity -> EntityValueSnapshot(value.id)
            is NarrativeValue.HostObject -> {
                @Suppress("UNCHECKED_CAST")
                (valueCodecs.codec(value.typeId) as NarrativeValueCodec<NarrativeValueSnapshot>)
                    .serialize(value.value)
            }
        }
    }

    private suspend fun restoreValue(
        snapshot: NarrativeValueSnapshot,
        context: NarrativeValueRestoreContext,
    ): NarrativeValue {
        return when (snapshot) {
            NullValueSnapshot -> NarrativeValue.Null
            is BoolValueSnapshot -> NarrativeValue.Bool(snapshot.value)
            is Int32ValueSnapshot -> NarrativeValue.Int32(snapshot.value)
            is TextValueSnapshot -> NarrativeValue.Text(snapshot.value)
            is EntityValueSnapshot -> NarrativeValue.Entity(snapshot.id)
            else -> {
                val codec = valueCodecs.codec(snapshot)
                NarrativeValue.HostObject(
                    typeId = codec.typeId,
                    value = codec.deserialize(snapshot, context),
                )
            }
        }
    }

    private fun serializeSlot(slot: NarrativeSlotValue): NarrativeSlotSnapshot {
        return when (slot) {
            is NarrativeSlotValue.Value -> NarrativeSlotSnapshot.Value(serializeValue(slot.value))
            is NarrativeSlotValue.VariableReference -> NarrativeSlotSnapshot.VariableReference(slot.name)
        }
    }

    private suspend fun restoreSlot(
        slot: NarrativeSlotSnapshot,
        context: NarrativeValueRestoreContext,
    ): NarrativeSlotValue {
        return when (slot) {
            is NarrativeSlotSnapshot.Value -> NarrativeSlotValue.Value(restoreValue(slot.value, context))
            is NarrativeSlotSnapshot.VariableReference -> NarrativeSlotValue.VariableReference(slot.name)
        }
    }

    private fun serializeStatus(status: NarrativeTaskStatus): NarrativeTaskStatusSnapshot {
        return when (status) {
            NarrativeTaskStatus.Ready -> NarrativeTaskStatusSnapshot.Ready
            is NarrativeTaskStatus.SuspendedCall -> NarrativeTaskStatusSnapshot.SuspendedCall(
                resultTarget = serializeResultTarget(status.resultTarget),
                nextInstructionPointer = status.nextInstructionPointer,
            )
            NarrativeTaskStatus.Completed -> NarrativeTaskStatusSnapshot.Completed
        }
    }

    private fun restoreStatus(status: NarrativeTaskStatusSnapshot): NarrativeTaskStatus {
        return when (status) {
            NarrativeTaskStatusSnapshot.Ready -> NarrativeTaskStatus.Ready
            is NarrativeTaskStatusSnapshot.SuspendedCall -> NarrativeTaskStatus.SuspendedCall(
                resultTarget = restoreResultTarget(status.resultTarget),
                nextInstructionPointer = status.nextInstructionPointer,
            )
            NarrativeTaskStatusSnapshot.Completed -> NarrativeTaskStatus.Completed
        }
    }

    private fun serializeResultTarget(target: NarrativeResultTarget?): NarrativeResultTargetSnapshot? {
        return when (target) {
            null -> null
            is NarrativeResultTarget.Variable -> NarrativeResultTargetSnapshot.Variable(target.name)
            is NarrativeResultTarget.Slot -> NarrativeResultTargetSnapshot.Slot(target.slot)
        }
    }

    private fun restoreResultTarget(target: NarrativeResultTargetSnapshot?): NarrativeResultTarget? {
        return when (target) {
            null -> null
            is NarrativeResultTargetSnapshot.Variable -> NarrativeResultTarget.Variable(target.name)
            is NarrativeResultTargetSnapshot.Slot -> NarrativeResultTarget.Slot(target.slot)
        }
    }
}
