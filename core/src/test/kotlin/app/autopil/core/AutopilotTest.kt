package app.autopil.core

import app.autopil.core.agent.ActionPacer
import app.autopil.core.agent.AgentRunner
import app.autopil.core.agent.DevicePort
import app.autopil.core.agent.Outcome
import app.autopil.core.apps.AppCatalog
import app.autopil.core.apps.NameForms
import app.autopil.core.match.ElementFinder
import app.autopil.core.match.ScreenReader
import app.autopil.core.model.Bounds
import app.autopil.core.model.Contact
import app.autopil.core.model.ContactMatch
import app.autopil.core.model.Direction
import app.autopil.core.model.FindResult
import app.autopil.core.model.FindRole
import app.autopil.core.model.GlobalAction
import app.autopil.core.model.OpenAppResult
import app.autopil.core.model.ScreenSnapshot
import app.autopil.core.model.UiNode
import app.autopil.core.parse.Command
import app.autopil.core.parse.CommandParser
import app.autopil.core.plan.Step
import app.autopil.core.plan.TaskPlanner
import app.autopil.core.safety.SafetyPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutopilotTest {
    private val parser = CommandParser()
    private val planner = TaskPlanner()
    private val finder = ElementFinder()
    private val safety = SafetyPolicy()

    @Test
    fun parsesTheFullSpokenRequest() {
        val command = parser.parse("Закажи такси до поликлиники и напиши брату, что я выехал")
        val steps = (command as Command.Sequence).steps
        assertEquals(Command.Taxi("поликлиники"), steps[0])
        assertEquals(Command.Message("брату", "я выехал"), steps[1])
    }

    @Test
    fun keepsMessageCaseAndStripsWakeWord() {
        val command = parser.parse("Автопил, напиши маме, что Я выехал")
        assertEquals(Command.Message("маме", "Я выехал"), command)
    }

    @Test
    fun doesNotSplitAndInsideTheMessage() {
        val command = parser.parse("напиши брату, что я и он выехали")
        assertEquals(Command.Message("брату", "я и он выехали"), command)
    }

    @Test
    fun parsesEverydayCommands() {
        assertEquals(Command.OpenApp("телеграм"), parser.parse("открой телеграм"))
        assertEquals(Command.Click("поиск"), parser.parse("нажми «поиск»"))
        assertEquals(Command.Click("Оплатить"), parser.parse("нажми кнопку Оплатить"))
        assertEquals(Command.Type("привет", null), parser.parse("введи привет"))
        assertEquals(Command.Type("поликлиника", "адрес"), parser.parse("введи в поле адрес поликлиника"))
        assertEquals(Command.Scroll(Direction.Forward), parser.parse("прокрути вниз"))
        assertEquals(Command.Stop, parser.parse("стоп"))
        assertEquals(Command.Confirm, parser.parse("подтверди"))
        assertEquals(Command.Skip, parser.parse("дальше"))
        assertEquals(Command.ReadScreen, parser.parse("что на экране"))
        assertEquals(Command.Call("брату"), parser.parse("позвони брату"))
        assertEquals(Command.Home, parser.parse("домой"))
    }

    @Test
    fun taxiGateComesBeforeTheMessageDraft() {
        val steps = planner.plan(parser.parse("Закажи такси до поликлиники и напиши брату, что я выехал"))
        val gate = steps.indexOfFirst { it is Step.Gate }
        val draft = steps.indexOfFirst { it is Step.DraftSms }
        assertTrue(gate in 0 until draft)
        val taxiGate = steps[gate] as Step.Gate
        assertTrue(taxiGate.queries.any { it.equals("Заказать", ignoreCase = true) })
        assertTrue(taxiGate.prompt.contains("подтверди"))
    }

    @Test
    fun clicksTheRowWhenTheLabelIsOnAChild() {
        val child = node("label", text = "Сохранить", bounds = Bounds(80, 80, 320, 150))
        val row = node("row", clickable = true, bounds = Bounds(40, 60, 1000, 180), children = listOf(child))
        val root = node("root", bounds = Bounds(0, 0, 1080, 2400), children = listOf(row))
        val result = finder.find(ScreenSnapshot(listOf(root), 1080, 2400), "Сохранить", FindRole.Clickable)
        val match = result as FindResult.Match
        assertEquals("row", match.node.id)
    }

    @Test
    fun matchesAClinicSuggestionByRussianEnding() {
        val result = finder.find(
            screen(node("s", text = "Городская поликлиника 1", clickable = true, bounds = Bounds(40, 400, 1000, 520))),
            "поликлиники",
            FindRole.Clickable,
        )
        assertEquals("s", (result as FindResult.Match).node.id)
    }

    @Test
    fun asksWhenTwoClinicsMatch() {
        val result = finder.find(
            screen(
                node("a", text = "Поликлиника 1", clickable = true, bounds = Bounds(40, 400, 1000, 520)),
                node("b", text = "Поликлиника 5", clickable = true, bounds = Bounds(40, 540, 1000, 660)),
            ),
            "поликлиника",
            FindRole.Clickable,
        )
        assertTrue(result is FindResult.Ambiguous)
    }

    @Test
    fun asksWhenTwoButtonsHaveTheSameName() {
        val result = finder.find(
            screen(
                node("a", text = "Ок", clickable = true, bounds = Bounds(40, 200, 300, 320)),
                node("b", text = "Ок", clickable = true, bounds = Bounds(340, 200, 600, 320)),
            ),
            "ок",
            FindRole.Clickable,
        )
        assertTrue(result is FindResult.Ambiguous)
    }

    @Test
    fun prefersTheExactButtonOverALongerLookalike() {
        val result = finder.find(
            screen(
                node("exact", text = "Ок", clickable = true, bounds = Bounds(40, 200, 300, 320)),
                node("long", text = "Окей", clickable = true, bounds = Bounds(40, 400, 300, 520)),
            ),
            "ок",
            FindRole.Clickable,
        )
        assertEquals("exact", (result as FindResult.Match).node.id)
    }

    @Test
    fun skipsDisabledAndDoesNotReadPasswords() {
        val disabled = finder.find(
            screen(node("off", text = "Поиск", clickable = true, enabled = false)),
            "поиск",
            FindRole.Clickable,
        )
        assertTrue(disabled is FindResult.None)
        val script = ScreenReader.script(
            screen(
                node("pw", text = "secret123", password = true, editable = true, bounds = Bounds(40, 200, 1000, 320)),
                node("title", text = "Вход", bounds = Bounds(40, 40, 400, 120)),
            ),
        )
        assertTrue(script.contains("поле пароля"))
        assertTrue(script.contains("Вход"))
        assertFalse(script.contains("secret123"))
    }

    @Test
    fun doesNotTreatTheWholeScreenAsAButton() {
        val result = finder.find(
            screen(node("root", text = "Поиск", clickable = true, bounds = Bounds(0, 0, 1080, 2400))),
            "поиск",
            FindRole.Clickable,
        )
        assertTrue(result is FindResult.None)
    }

    @Test
    fun paymentSendAndDeleteNeedConfirmation() {
        assertTrue(safety.isSensitiveLabel("Заказать"))
        assertTrue(safety.isSensitiveLabel("Оплатить картой"))
        assertTrue(safety.isSensitiveLabel("Отправить"))
        assertTrue(safety.isSensitiveLabel("Удалить"))
        assertTrue(safety.isSensitiveLabel("Сбросить настройки телефона полностью"))
        assertFalse(safety.isSensitiveLabel("Поиск"))
        assertFalse(safety.isSensitiveLabel("Куда едем"))
        assertFalse(safety.isSensitiveLabel("История заказов за прошлый месяц"))
    }

    @Test
    fun contactNamesSurviveDativeCase() {
        assertTrue(NameForms.score("брату", "Брат") >= 100)
        assertTrue(NameForms.score("маме", "Мама") >= 100)
        assertTrue(NameForms.score("Марии", "Мария") >= 80)
    }

    @Test
    fun appLabelsDoNotConfuseGoWithGoogle() {
        val taxi = AppCatalog.searchTerms("такси")
        assertEquals("Uber", AppCatalog.bestLabel(listOf("Камера", "Uber", "Карты"), taxi))
        assertEquals("Яндекс Go", AppCatalog.bestLabel(listOf("Google", "Яндекс Go"), taxi))
        assertNull(AppCatalog.bestLabel(listOf("Google", "Карты"), taxi))
        assertEquals("Сообщения", AppCatalog.bestLabel(listOf("Телефон", "Сообщения"), AppCatalog.searchTerms("сообщения")))
    }

    @Test
    fun oneSentenceStopsBeforeOrderAndSend() = runBlocking {
        val port = FakePort()
        val runner = runner(port)
        val outcome = runner.onUtterance("Закажи такси до поликлиники и напиши брату, что я выехал")
        assertTrue(outcome is Outcome.Waiting)
        assertEquals(listOf("такси"), port.opened)
        assertTrue(port.typed.any { it.contains("поликлиники") })
        assertTrue(port.clicks.any { it.contains("поликлиник", ignoreCase = true) })
        assertFalse(port.clicks.any { it.contains("заказ", ignoreCase = true) })
        assertFalse(port.clicks.any { it.equals("Отправить", ignoreCase = true) })
        assertTrue(port.drafts.isEmpty())
        assertTrue(port.spoken.any { it.contains("подтверди") })

        val skipped = runner.onUtterance("дальше")
        assertTrue(skipped is Outcome.Waiting)
        assertEquals(listOf("Брат"), port.drafts.map { it.name })
        assertEquals(listOf("я выехал"), port.drafts.map { it.body })
        assertFalse(port.clicks.any { it.equals("Отправить", ignoreCase = true) })

        val sent = runner.onUtterance("подтверди")
        assertTrue(sent is Outcome.Done)
        assertTrue(port.clicks.any { it.equals("Отправить", ignoreCase = true) })
        assertFalse(port.clicks.any { it.equals("Заказать", ignoreCase = true) })
    }

    @Test
    fun confirmingTheTaxiStillDoesNotSendTheMessage() = runBlocking {
        val port = FakePort()
        val runner = runner(port)
        runner.onUtterance("Закажи такси до поликлиники и напиши брату, что я выехал")
        val ordered = runner.onUtterance("подтверди")
        assertTrue(ordered is Outcome.Waiting)
        assertTrue(port.clicks.any { it.equals("Заказать", ignoreCase = true) })
        assertFalse(port.clicks.any { it.equals("Отправить", ignoreCase = true) })
        assertEquals(1, port.drafts.size)
    }

    @Test
    fun missingTaxiAppStillPreparesTheMessage() = runBlocking {
        val port = FakePort(blockTaxi = true)
        val outcome = runner(port).onUtterance("Закажи такси до поликлиники и напиши брату, что я выехал")
        assertTrue(outcome is Outcome.Waiting)
        assertTrue(port.drafts.isNotEmpty())
        assertTrue(port.clicks.none { it.equals("Заказать", ignoreCase = true) })
    }

    @Test
    fun namingPayStillWaitsForASecondWord() = runBlocking {
        val port = FakePort()
        port.screen = screen(node("pay", text = "Оплатить", clickable = true, className = "android.widget.Button"))
        val runner = runner(port)
        val waiting = runner.onUtterance("нажми оплатить")
        assertTrue(waiting is Outcome.Waiting)
        assertTrue(port.clicks.isEmpty())
        val done = runner.onUtterance("подтверди")
        assertTrue(done is Outcome.Done)
        assertEquals(listOf("Оплатить"), port.clicks)
    }

    @Test
    fun ordinaryButtonIsPressedWithoutASecondConfirmation() = runBlocking {
        val port = FakePort()
        port.screen = screen(node("search", text = "Поиск", clickable = true, className = "android.widget.Button"))
        val outcome = runner(port).onUtterance("нажми поиск")
        assertTrue(outcome is Outcome.Done)
        assertEquals(listOf("Поиск"), port.clicks)
    }

    @Test
    fun pauseIsSteadyAndStopDuringItPreventsTheClick() = runBlocking {
        var now = 0L
        var clickAt = -1L
        val port = FakePort()
        port.screen = screen(node("search", text = "Поиск", clickable = true))
        port.onClick = { clickAt = now }
        val runner = AgentRunner(
            port = port,
            pacer = ActionPacer(
                beforeMs = { 500 },
                afterMs = { 800 },
                sleep = { now += it },
            ),
        )
        runner.onUtterance("нажми поиск")
        assertEquals(500L, clickAt)
        assertEquals(1300L, now)

        now = 0
        clickAt = -1
        port.clicks.clear()
        var aborted = false
        val stopped = AgentRunner(
            port = port,
            pacer = ActionPacer(
                beforeMs = { 100 },
                afterMs = { 100 },
                sleep = { aborted = true },
                shouldAbort = { aborted },
            ),
        )
        val outcome = stopped.onUtterance("нажми поиск")
        assertTrue(outcome is Outcome.Aborted)
        assertTrue(port.clicks.isEmpty())
        assertEquals(-1L, clickAt)
    }

    @Test
    fun doesNotTypeIntoAPasswordUnlessTheFieldWasNamed() = runBlocking {
        val port = FakePort()
        port.screen = screen(node("pw", hint = "Пароль", editable = true, password = true, bounds = Bounds(40, 200, 1000, 340)))
        val refused = runner(port).onUtterance("введи secret123")
        assertTrue(refused is Outcome.Failed)
        assertTrue(port.typed.isEmpty())
        assertFalse(port.spoken.any { it.contains("secret123") })
        assertFalse(port.logs.any { it.contains("secret123") })

        val allowed = runner(port).onUtterance("введи в поле пароль secret123")
        assertTrue(allowed is Outcome.Done || allowed is Outcome.Continue)
        assertEquals(listOf("secret123"), port.typed)
        assertFalse(port.logs.any { it.contains("secret123") })
        assertFalse(port.spoken.any { it.contains("secret123") })
    }

    @Test
    fun scrollsUntilTheButtonAppears() = runBlocking {
        val port = FakePort()
        port.screen = screen(node("title", text = "Меню"))
        port.onScroll = {
            port.screen = screen(node("search", text = "Поиск", clickable = true, className = "android.widget.Button"))
        }
        val outcome = runner(port).onUtterance("нажми поиск")
        assertTrue(outcome is Outcome.Done)
        assertEquals(1, port.scrolls)
        assertEquals(listOf("Поиск"), port.clicks)
    }

    private fun runner(port: FakePort): AgentRunner {
        return AgentRunner(
            port = port,
            pacer = ActionPacer(beforeMs = { 0 }, afterMs = { 0 }, sleep = { }),
        )
    }

    private fun screen(vararg nodes: UiNode) = ScreenSnapshot(nodes.toList(), 1080, 2400)

    private fun node(
        id: String,
        text: String = "",
        hint: String = "",
        description: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        password: Boolean = false,
        className: String = "",
        bounds: Bounds = Bounds(40, 200, 1000, 340),
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        id = id,
        text = text,
        hint = hint,
        description = description,
        clickable = clickable,
        editable = editable,
        enabled = enabled,
        password = password,
        className = className,
        bounds = bounds,
        children = children,
    )
}

private class FakePort(private val blockTaxi: Boolean = false) : DevicePort {
    var screen: ScreenSnapshot = ScreenSnapshot(emptyList(), 1080, 2400)
    val opened = mutableListOf<String>()
    val clicks = mutableListOf<String>()
    val typed = mutableListOf<String>()
    val spoken = mutableListOf<String>()
    val logs = mutableListOf<String>()
    val drafts = mutableListOf<Draft>()
    var scrolls = 0
    var onClick: () -> Unit = {}
    var onScroll: () -> Unit = {}

    override suspend fun capture(): ScreenSnapshot = screen

    override suspend fun highlight(bounds: Bounds?) = Unit

    override suspend fun click(target: UiNode): Boolean {
        val label = target.label()
        clicks += label
        onClick()
        when {
            label.contains("Куда", ignoreCase = true) -> screen = taxiField()
            label.contains("поликлиник", ignoreCase = true) -> screen = readyToOrder()
            label.equals("Заказать", ignoreCase = true) -> screen = ordered()
            label.equals("Отправить", ignoreCase = true) -> screen = sent()
        }
        return true
    }

    override suspend fun typeText(target: UiNode?, text: String): Boolean {
        typed += text
        if (text.contains("поликлиник", ignoreCase = true)) screen = suggestions(text)
        return true
    }

    override suspend fun scroll(direction: Direction): Boolean {
        scrolls += 1
        onScroll()
        return true
    }

    override suspend fun global(action: GlobalAction): Boolean = true

    override suspend fun openApp(name: String, packages: List<String>, terms: List<String>): OpenAppResult {
        opened += name
        if (blockTaxi && (name.contains("так", ignoreCase = true) || packages.isNotEmpty())) {
            return OpenAppResult.NotFound
        }
        if (packages.isNotEmpty()) screen = taxiField()
        return OpenAppResult.Opened(name, packages.firstOrNull() ?: "app.fake")
    }

    override suspend fun speak(text: String) {
        spoken += text
    }

    override suspend fun log(line: String) {
        logs += line
    }

    override suspend fun resolveContact(spokenName: String): ContactMatch {
        return if (NameForms.score(spokenName, "Брат") >= 70) {
            ContactMatch.Found(Contact("Брат", "+79990001122"))
        } else {
            ContactMatch.NotFound
        }
    }

    override suspend fun showSmsDraft(phone: String, displayName: String, body: String): Boolean {
        drafts += Draft(displayName, body)
        screen = smsReady()
        return true
    }

    override suspend fun showDialer(phone: String, displayName: String): Boolean = true

    data class Draft(val name: String, val body: String)

    private fun taxiField() = ScreenSnapshot(
        listOf(
            UiNode(
                id = "where",
                hint = "Куда едем",
                editable = true,
                clickable = true,
                bounds = Bounds(40, 180, 1040, 340),
            ),
        ),
        1080,
        2400,
    )

    private fun suggestions(text: String) = ScreenSnapshot(
        listOf(
            UiNode(id = "where", text = text, hint = "Куда едем", editable = true, bounds = Bounds(40, 180, 1040, 340)),
            UiNode(id = "s1", text = "Городская поликлиника 1", clickable = true, bounds = Bounds(40, 360, 1040, 500)),
            UiNode(id = "s2", text = "Поликлиника 5", clickable = true, bounds = Bounds(40, 520, 1040, 660)),
        ),
        1080,
        2400,
    )

    private fun readyToOrder() = ScreenSnapshot(
        listOf(
            UiNode(id = "route", text = "Городская поликлиника 1", bounds = Bounds(40, 180, 1040, 300)),
            UiNode(
                id = "order",
                text = "Заказать",
                clickable = true,
                className = "android.widget.Button",
                bounds = Bounds(40, 1800, 1040, 1960),
            ),
        ),
        1080,
        2400,
    )

    private fun ordered() = readyToOrder()

    private fun smsReady() = ScreenSnapshot(
        listOf(
            UiNode(id = "body", text = "я выехал", editable = true, bounds = Bounds(40, 1500, 900, 1680)),
            UiNode(
                id = "send",
                text = "Отправить",
                clickable = true,
                className = "android.widget.Button",
                bounds = Bounds(900, 1500, 1040, 1680),
            ),
        ),
        1080,
        2400,
    )

    private fun sent() = smsReady()
}
