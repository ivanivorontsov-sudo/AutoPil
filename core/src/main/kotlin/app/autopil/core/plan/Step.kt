package app.autopil.core.plan

import app.autopil.core.model.Direction
import app.autopil.core.model.FindRole
import app.autopil.core.model.GlobalAction

sealed class Step {
    data object BeginTask : Step()
    data class Say(val text: String) : Step()
    data class Launch(val name: String, val packages: List<String>, val terms: List<String>) : Step()
    data class Tap(
        val query: String,
        val role: FindRole = FindRole.Clickable,
        val scrollIfMissing: Boolean = true,
        val optional: Boolean = false,
    ) : Step()
    data class TapFirst(
        val queries: List<String>,
        val role: FindRole,
        val optional: Boolean = false,
    ) : Step()
    data class Enter(val text: String, val field: String?) : Step()
    data class ScrollTo(val direction: Direction) : Step()
    data class System(val action: GlobalAction) : Step()
    data object AnnounceScreen : Step()
    data class Gate(val prompt: String, val queries: List<String>) : Step()
    data class DraftSms(val who: String, val body: String) : Step()
    data class Dial(val who: String) : Step()
}
