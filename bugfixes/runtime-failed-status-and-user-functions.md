# runtime failed state + top-level user functions

## Problem

1. Runtime errors were thrown from execution coroutines without a structured task-level failed state.
   This made production handling ambiguous (state inspection and `join()` behavior under failures were not explicit).

2. Narrative scripts could not define reusable user functions directly in script files (including parameter passing and return values).
3. `choose` operator left temporary variables (`__narrative_choose_*`) in locals after branch completion.

## Fix

- Added `NarrativeTaskStatus.Failed(message)` and matching snapshot type `NarrativeTaskStatusSnapshot.Failed`.
- Extended status serialization/restore in `NarrativeStateSnapshotCodec`.
- Added guarded error handling in runtime execution paths:
  - instruction execution loop,
  - suspended function dispatch,
  - suspended function resume.
- On runtime error, task is marked `Failed` with contextual message (including source position where available).
- Completion condition now treats both `Completed` and `Failed` as terminal task statuses.

- Added support for top-level user narrative functions with parameters and return values:
  - syntax: `fun name(a: Int, b: Int): Int { ... }`
  - call as statement or expression: `name(...)`
- Current constraints are explicit and validated at compile time:
  - top-level only,
  - no receiver,
  - no type parameters.
- Recursive user-function calls are rejected explicitly.
- Return handling:
  - return value is taken from trailing expression, or trailing `return`.
  - only a single trailing `return` is supported (early/nested returns are rejected explicitly).
- Call-scoped variables are isolated via compiler aliasing and cleaned automatically after invocation.

- Added `RemoveVariablesInstruction` in VM/runtime and used it for temporary cleanup.
- `choose` compilation now uses internal `chooseIndexed` (host-facing option ids remain unchanged),
  and temporary selection variables are removed after branch resolution.

## Regression coverage

- `runtimeMarksTaskAsFailedAndJoinCompletesOnExecutionError`
- `topLevelUserFunctionsCanBeCalledFromNarrativeScript`
- `userFunctionsSupportParametersAndReturnValues`
- `chooseTemporaryVariablesAreCleanedAfterBranchExecution`
