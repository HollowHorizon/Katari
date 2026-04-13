## Narrative demo restore/continue bug

### Symptoms

- After loading a saved narrative snapshot, the demo window stayed empty.
- The `Continue` button no longer resumed the script.

### Root causes

1. `NarrativeInstance.cancel()` always cancelled the provided `CoroutineScope`.
   In the desktop demo all instances shared one external scope, so loading a snapshot cancelled the shared scope and every new instance created afterwards was already dead.

2. `NarrativeInstance` still tracked suspended calls through `effectId`, while the task status model had already been simplified and no longer used that field consistently.
   After restore, suspended calls were not matched and dispatched reliably.

### Fix

- `NarrativeInstance` now cancels only its own internally created scope.
- When an external scope is injected, `cancel()` only stops the instance job itself.
- Suspended dispatch/resume tracking now uses `taskId` instead of the removed `effectId`.

### Result

- A restored `NarrativeInstance` can be recreated on the same external scope.
- Suspended `say`/`narrate`/`choose` calls are dispatched again after loading.
- The demo can continue from a saved state instead of getting stuck after restore.
