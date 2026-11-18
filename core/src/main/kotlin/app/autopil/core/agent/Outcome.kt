package app.autopil.core.agent

sealed class Outcome {
    data object Continue : Outcome()
    data object Done : Outcome()
    data object Stopped : Outcome()
    data object Aborted : Outcome()
    data object Failed : Outcome()
    data object NotFound : Outcome()
    data object Ambiguous : Outcome()
    data object Idle : Outcome()
    data class Waiting(val prompt: String) : Outcome()
}
