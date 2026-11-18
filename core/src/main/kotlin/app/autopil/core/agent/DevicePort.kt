package app.autopil.core.agent

import app.autopil.core.model.Bounds
import app.autopil.core.model.ContactMatch
import app.autopil.core.model.Direction
import app.autopil.core.model.GlobalAction
import app.autopil.core.model.OpenAppResult
import app.autopil.core.model.ScreenSnapshot
import app.autopil.core.model.UiNode

interface DevicePort {
    suspend fun capture(): ScreenSnapshot
    suspend fun highlight(bounds: Bounds?)
    suspend fun click(target: UiNode): Boolean
    suspend fun typeText(target: UiNode?, text: String): Boolean
    suspend fun scroll(direction: Direction): Boolean
    suspend fun global(action: GlobalAction): Boolean
    suspend fun openApp(name: String, packages: List<String>, terms: List<String>): OpenAppResult
    suspend fun speak(text: String)
    suspend fun log(line: String)
    suspend fun resolveContact(spokenName: String): ContactMatch
    suspend fun showSmsDraft(phone: String, displayName: String, body: String): Boolean
    suspend fun showDialer(phone: String, displayName: String): Boolean
}
