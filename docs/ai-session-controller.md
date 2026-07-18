# Foreground AI session controller

`AiSessionController` owns the lifecycle shared by every foreground AI action while leaving action-specific behavior inside `KeyboardViewModel`.

## Responsibilities

- expose the single active `AiPanelState`
- cancel the previous foreground request when a newer action starts
- publish `Loading` before an action runs
- return to `Idle` when an action exits without publishing a result
- keep completed results visible
- dismiss the active panel
- store and invoke the latest regenerate callback

## Deliberate boundary

The controller does not decide which model to use, build personalization context, write learning data, or persist usage logs. Those choices still belong to `KeyboardViewModel` and the existing AI/network layer.

This extraction creates a tested seam for moving individual AI actions out of the ViewModel in later refactors without changing the UI contract introduced by PR #45.
