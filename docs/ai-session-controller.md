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
- cancel active work when the ViewModel is cleared

## ViewModel integration

`KeyboardViewModel` constructs one controller with `viewModelScope` and exposes the controller's `panelState` as its existing public `aiPanelState` contract. The ViewModel delegates launch, cancellation, loading cleanup, dismissal, and regeneration to the controller.

A small internal mutable-state bridge lets the existing action methods continue publishing their action-specific results without a large behavioral rewrite. The controller still owns the backing state. Later action-orchestration refactors can replace those direct writes with `publish` one method at a time.

## Deliberate boundary

The controller does not decide which model to use, build personalization context, write learning data, or persist usage logs. Those choices remain in `KeyboardViewModel` and the existing AI/network layer.

This extraction creates a tested seam for moving individual AI actions out of the ViewModel without changing the UI contract introduced by PR #45.
