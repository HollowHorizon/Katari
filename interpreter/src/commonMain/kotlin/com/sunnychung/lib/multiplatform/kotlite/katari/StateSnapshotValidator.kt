package com.sunnychung.lib.multiplatform.kotlite.katari

class StateSnapshotValidationException(
    val diagnostics: List<StateSnapshotDiagnostic>,
) : IllegalArgumentException(diagnostics.joinToString(separator = "\n") { it.message })

data class StateSnapshotDiagnostic(
    val path: String,
    val message: String,
)

object StateSnapshotValidator {
    fun validate(snapshot: KatariStateSnapshot) {
        val diagnostics = buildList {
            validateValueIds(snapshot)
            validateTasks(snapshot)
        }
        if (diagnostics.isNotEmpty()) {
            throw StateSnapshotValidationException(diagnostics)
        }
    }

    private fun MutableList<StateSnapshotDiagnostic>.validateValueIds(snapshot: KatariStateSnapshot) {
        snapshot.tasks.forEachIndexed { taskIndex, task ->
            task.variableRefs.forEach { (name, ref) ->
                validateValueRef(
                    path = "tasks[$taskIndex].variableRefs[$name]",
                    ref = ref,
                    values = snapshot.values,
                )
            }
            task.callFrames.forEachIndexed { frameIndex, frame ->
                frame.variableRefs.forEach { (name, ref) ->
                    validateValueRef(
                        path = "tasks[$taskIndex].callFrames[$frameIndex].variableRefs[$name]",
                        ref = ref,
                        values = snapshot.values,
                    )
                }
            }
        }
    }

    private fun MutableList<StateSnapshotDiagnostic>.validateValueRef(
        path: String,
        ref: ValueReferenceSnapshot,
        values: Map<Int, ValueSnapshot>,
    ) {
        if (ref.valueId !in values) {
            add(
                StateSnapshotDiagnostic(
                    path = path,
                    message = "$path references missing snapshot value `${ref.valueId}`",
                )
            )
        }
    }

    private fun MutableList<StateSnapshotDiagnostic>.validateTasks(snapshot: KatariStateSnapshot) {
        val taskIds = mutableSetOf<String>()
        snapshot.tasks.forEachIndexed { taskIndex, task ->
            val taskPath = "tasks[$taskIndex]"
            if (!taskIds.add(task.id)) {
                add(
                    StateSnapshotDiagnostic(
                        path = "$taskPath.id",
                        message = "$taskPath.id duplicates task id `${task.id}`",
                    )
                )
            }
            validateCallFrames(taskPath, task)
        }
    }

    private fun MutableList<StateSnapshotDiagnostic>.validateCallFrames(
        taskPath: String,
        task: TaskSnapshot,
    ) {
        val frameIds = mutableSetOf<Int>()
        task.callFrames.forEachIndexed { frameIndex, frame ->
            val framePath = "$taskPath.callFrames[$frameIndex]"
            if (!frameIds.add(frame.id)) {
                add(
                    StateSnapshotDiagnostic(
                        path = "$framePath.id",
                        message = "$framePath.id duplicates call frame id `${frame.id}` in task `${task.id}`",
                    )
                )
            }
        }
        task.callFrames.forEachIndexed { frameIndex, frame ->
            val framePath = "$taskPath.callFrames[$frameIndex]"
            val parentId = frame.lexicalParentFrameId
            if (parentId != null && parentId !in frameIds) {
                add(
                    StateSnapshotDiagnostic(
                        path = "$framePath.lexicalParentFrameId",
                        message = "$framePath.lexicalParentFrameId references missing call frame `$parentId`",
                    )
                )
            }
        }
        val maxFrameId = frameIds.maxOrNull() ?: ROOT_CALL_FRAME_ID
        if (task.nextCallFrameId <= maxFrameId) {
            add(
                StateSnapshotDiagnostic(
                    path = "$taskPath.nextCallFrameId",
                    message = "$taskPath.nextCallFrameId must be greater than existing frame id `$maxFrameId`",
                )
            )
        }
    }
}
