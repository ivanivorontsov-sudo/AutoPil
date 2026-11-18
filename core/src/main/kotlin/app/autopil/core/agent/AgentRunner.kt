package app.autopil.core.agent

import app.autopil.core.apps.AppCatalog
import app.autopil.core.match.ElementFinder
import app.autopil.core.match.ScreenReader
import app.autopil.core.match.TextNormalizer
import app.autopil.core.model.ContactMatch
import app.autopil.core.model.Direction
import app.autopil.core.model.FindResult
import app.autopil.core.model.FindRole
import app.autopil.core.model.OpenAppResult
import app.autopil.core.model.ScreenSnapshot
import app.autopil.core.model.UiNode
import app.autopil.core.model.flatten
import app.autopil.core.parse.Command
import app.autopil.core.parse.CommandParser
import app.autopil.core.plan.Step
import app.autopil.core.plan.TaskPlanner
import app.autopil.core.safety.SafetyPolicy

class AgentRunner(
    private val port: DevicePort,
    private val parser: CommandParser = CommandParser(),
    private val planner: TaskPlanner = TaskPlanner(),
    private val finder: ElementFinder = ElementFinder(),
    private val safety: SafetyPolicy = SafetyPolicy(),
    private val pacer: ActionPacer,
) {
    private var pending: Step.Gate? = null
    private var queue: List<Step> = emptyList()

    val isWaiting: Boolean get() = pending != null

    suspend fun abandon() {
        pending = null
        queue = emptyList()
        port.highlight(null)
    }

    suspend fun onUtterance(raw: String): Outcome {
        return when (val command = parser.parse(raw)) {
            Command.Stop -> stop(spoken = "Остановлено")
            Command.Reject -> stop(spoken = "Отменено")
            Command.Confirm -> confirm()
            Command.Skip -> skip()
            else -> {
                if (pending != null) {
                    port.speak("Нужно сказать «подтверди», «дальше» или «отмена»")
                    Outcome.Waiting(pending?.prompt.orEmpty())
                } else {
                    queue = emptyList()
                    run(planner.plan(command))
                }
            }
        }
    }

    private suspend fun stop(spoken: String): Outcome {
        pending = null
        queue = emptyList()
        port.highlight(null)
        port.speak(spoken)
        port.log(spoken)
        return Outcome.Stopped
    }

    private suspend fun confirm(): Outcome {
        val gate = pending
        if (gate == null) {
            port.speak("Сейчас нечего подтверждать")
            return Outcome.Idle
        }
        val rest = queue
        pending = null
        queue = emptyList()
        val clicked = tapQueries(
            queries = gate.queries,
            role = FindRole.Clickable,
            scroll = false,
            optional = false,
            sensitiveAllowed = true,
            announceMiss = false,
        )
        return when (clicked) {
            is Outcome.Aborted -> clicked
            Outcome.Continue -> if (rest.isEmpty()) Outcome.Done else run(rest)
            is Outcome.Waiting -> {
                queue = rest
                clicked
            }
            else -> {
                pending = gate
                queue = rest
                port.speak("Кнопку подтверждения не нашла. Скажите «подтверди» ещё раз или «отмена».")
                Outcome.Waiting(gate.prompt)
            }
        }
    }

    private suspend fun skip(): Outcome {
        if (pending == null && queue.isEmpty()) {
            port.speak("Дальше ничего нет")
            return Outcome.Idle
        }
        pending = null
        port.speak("Подтверждение пропущено")
        port.log("Подтверждение пропущено")
        val rest = queue
        queue = emptyList()
        return if (rest.isEmpty()) Outcome.Done else run(rest)
    }

    private suspend fun run(steps: List<Step>): Outcome {
        for ((index, step) in steps.withIndex()) {
            if (pacer.isAborted()) return abort()
            when (step) {
                Step.BeginTask -> Unit
                is Step.Say -> port.speak(step.text)
                is Step.Launch -> {
                    when (val opened = port.openApp(step.name, step.packages, step.terms)) {
                        is OpenAppResult.Opened -> {
                            port.log("Открыто: ${opened.label}")
                            if (!pacer.afterAction() || !pacer.settle(APP_SETTLE_MS)) return abort()
                        }
                        OpenAppResult.NotFound -> {
                            port.speak("Приложение «${step.name}» не найдено")
                            port.log("Не найдено приложение «${step.name}»")
                            return failOrNext(steps, index, Outcome.NotFound)
                        }
                    }
                }
                is Step.Tap -> {
                    val result = tapQueries(
                        queries = listOf(step.query),
                        role = step.role,
                        scroll = step.scrollIfMissing,
                        optional = step.optional,
                        sensitiveAllowed = false,
                    )
                    when (result) {
                        is Outcome.Waiting -> {
                            queue = steps.drop(index + 1)
                            return result
                        }
                        Outcome.Continue -> Unit
                        is Outcome.Aborted -> return result
                        else -> return failOrNext(steps, index, result)
                    }
                }
                is Step.TapFirst -> {
                    val result = tapQueries(
                        queries = step.queries,
                        role = step.role,
                        scroll = false,
                        optional = step.optional,
                        sensitiveAllowed = false,
                    )
                    when (result) {
                        is Outcome.Waiting -> {
                            queue = steps.drop(index + 1)
                            return result
                        }
                        Outcome.Continue -> Unit
                        is Outcome.Aborted -> return result
                        else -> return failOrNext(steps, index, result)
                    }
                }
                is Step.Enter -> when (val result = enter(step)) {
                    Outcome.Continue -> Unit
                    is Outcome.Aborted -> return result
                    else -> return failOrNext(steps, index, result)
                }
                is Step.ScrollTo -> {
                    if (!pacer.beforeAction()) return abort()
                    port.scroll(step.direction)
                    port.log("Прокрутка")
                    if (!pacer.afterAction()) return abort()
                }
                is Step.System -> {
                    if (!pacer.beforeAction()) return abort()
                    if (!port.global(step.action)) {
                        port.speak("Это действие недоступно на этой версии Android")
                        return failOrNext(steps, index, Outcome.Failed)
                    }
                    port.log(systemLabel(step.action))
                    if (!pacer.afterAction()) return abort()
                }
                Step.AnnounceScreen -> port.speak(ScreenReader.script(port.capture()))
                is Step.Gate -> {
                    pending = step
                    queue = steps.drop(index + 1)
                    port.speak(step.prompt)
                    port.log("Остановка перед подтверждением")
                    return Outcome.Waiting(step.prompt)
                }
                is Step.DraftSms -> when (val result = draftSms(step)) {
                    Outcome.Continue -> Unit
                    is Outcome.Aborted -> return result
                    is Outcome.Waiting -> {
                        queue = steps.drop(index + 1)
                        return result
                    }
                    else -> return failOrNext(steps, index, result)
                }
                is Step.Dial -> when (val result = dial(step)) {
                    Outcome.Continue -> Unit
                    is Outcome.Aborted -> return result
                    is Outcome.Waiting -> {
                        queue = steps.drop(index + 1)
                        return result
                    }
                    else -> return failOrNext(steps, index, result)
                }
            }
        }
        return Outcome.Done
    }

    private suspend fun failOrNext(steps: List<Step>, index: Int, outcome: Outcome): Outcome {
        val rest = steps.drop(index + 1)
        val next = rest.indexOfFirst { it is Step.BeginTask }
        if (next == -1) return outcome
        return run(rest.drop(next))
    }

    private suspend fun tapQueries(
        queries: List<String>,
        role: FindRole,
        scroll: Boolean,
        optional: Boolean,
        sensitiveAllowed: Boolean,
        announceMiss: Boolean = true,
    ): Outcome {
        val attempts = if (scroll) 4 else 1
        for (attempt in 0 until attempts) {
            for (query in queries) {
                when (val found = finder.find(port.capture(), query, role)) {
                    is FindResult.Match -> return actOn(found.node, query, role, sensitiveAllowed)
                    is FindResult.Ambiguous -> {
                        if (optional) {
                            val choice = found.options.first().node
                            port.speak("Первая подсказка: ${choice.label().ifBlank { query }}")
                            return actOn(choice, query, role, sensitiveAllowed)
                        }
                        val labels = found.options.map { it.node.label().ifBlank { query } }.distinct().take(3)
                        port.speak("Несколько кнопок: ${labels.joinToString(", ")}. Скажите точнее.")
                        return Outcome.Ambiguous
                    }
                    FindResult.None -> Unit
                }
            }
            if (attempt < attempts - 1) {
                if (!pacer.beforeAction()) return abort()
                if (!port.scroll(Direction.Forward)) break
                if (!pacer.afterAction()) return abort()
            }
        }
        if (optional) {
            port.log("Шаг «${queries.firstOrNull().orEmpty()}» пропущен")
            return Outcome.Continue
        }
        if (announceMiss) port.speak("Кнопку «${queries.firstOrNull().orEmpty()}» не нашла")
        return Outcome.NotFound
    }

    private suspend fun actOn(node: UiNode, query: String, role: FindRole, sensitiveAllowed: Boolean): Outcome {
        if (!sensitiveAllowed && safety.isSensitive(node)) {
            val label = node.label().ifBlank { query }
            val gate = Step.Gate(
                prompt = "Кнопка «$label» ждёт подтверждения. Скажите «подтверди», если её нажать.",
                queries = listOf(label),
            )
            pending = gate
            port.speak(gate.prompt)
            port.log("Остановка перед «$label»")
            return Outcome.Waiting(gate.prompt)
        }
        return performClick(node, query, role)
    }

    private suspend fun performClick(node: UiNode, query: String, role: FindRole): Outcome {
        port.highlight(node.bounds)
        if (!pacer.beforeAction()) {
            port.highlight(null)
            return abort()
        }
        val fresh = port.capture()
        val target = findSame(fresh, node)
            ?: (finder.find(fresh, node.label().ifBlank { query }, role) as? FindResult.Match)?.node
            ?: (finder.find(fresh, query, role) as? FindResult.Match)?.node
        if (target == null) {
            port.highlight(null)
            port.speak("Кнопка исчезла до нажатия")
            return Outcome.Failed
        }
        val clicked = port.click(target)
        port.highlight(null)
        if (!clicked) {
            port.speak("Не удалось нажать «${target.label().ifBlank { query }}»")
            return Outcome.Failed
        }
        port.log("Нажато «${target.label().ifBlank { query }}»")
        if (!pacer.afterAction()) return abort()
        return Outcome.Continue
    }

    private fun findSame(screen: ScreenSnapshot, node: UiNode): UiNode? {
        val want = TextNormalizer.normalize(node.label())
        val nodes = screen.flatten()
        return nodes.firstOrNull { it.bounds == node.bounds && TextNormalizer.normalize(it.label()) == want }
            ?: nodes.firstOrNull {
                TextNormalizer.normalize(it.label()) == want && want.isNotBlank() && it.clickable == node.clickable
            }
    }

    private suspend fun enter(step: Step.Enter): Outcome {
        val screen = port.capture()
        val target = if (step.field != null) {
            when (val found = finder.find(screen, step.field, FindRole.Editable)) {
                is FindResult.Match -> found.node
                is FindResult.Ambiguous -> found.options.first().node
                FindResult.None -> {
                    port.speak("Поле «${step.field}» не найдено")
                    return Outcome.NotFound
                }
            }
        } else {
            screen.flatten().firstOrNull { it.editable && it.enabled && it.visible && !it.password }
                ?: screen.flatten().firstOrNull { it.editable && it.enabled && it.visible }
        }
        if (target?.password == true && !explicitPassword(step.field)) {
            port.speak("Поле пароля пропущено. Можно сказать: введи в поле пароль и сам текст.")
            port.log("Поле пароля пропущено")
            return Outcome.Failed
        }
        if (target != null) {
            port.highlight(target.bounds)
            if (!pacer.beforeAction()) return abort()
        }
        val fresh = port.capture()
        val freshTarget = when {
            step.field != null -> (finder.find(fresh, step.field, FindRole.Editable) as? FindResult.Match)?.node
            target?.password == true -> fresh.flatten().firstOrNull { it.password && it.editable }
            else -> fresh.flatten().firstOrNull { it.editable && it.enabled && it.visible && !it.password }
        }
        if (freshTarget?.password == true && !explicitPassword(step.field)) {
            port.highlight(null)
            port.speak("Поле пароля пропущено. Можно сказать: введи в поле пароль и сам текст.")
            return Outcome.Failed
        }
        val typed = port.typeText(freshTarget ?: target, step.text)
        port.highlight(null)
        if (!typed) {
            port.speak("Не удалось ввести текст")
            return Outcome.Failed
        }
        port.log(if (freshTarget?.password == true) "Введён текст в поле пароля" else "Введён текст")
        if (!pacer.afterAction()) return abort()
        return Outcome.Continue
    }

    private fun explicitPassword(field: String?): Boolean {
        val spoken = TextNormalizer.normalize(field ?: return false)
        return spoken.contains("парол") || spoken.contains("password")
    }

    private suspend fun draftSms(step: Step.DraftSms): Outcome {
        return when (val match = port.resolveContact(step.who)) {
            is ContactMatch.Found -> openDraft(match.contact.phone, match.contact.displayName, step.body)
            is ContactMatch.Ambiguous -> {
                val names = match.contacts.take(3).joinToString(", ") { it.displayName }
                port.speak("Несколько контактов: $names. Скажите имя точнее.")
                Outcome.Ambiguous
            }
            ContactMatch.NoPermission -> {
                port.speak("Нет доступа к контактам. Ищу «${step.who}» в приложении сообщений.")
                draftOnScreen(step)
            }
            ContactMatch.NotFound -> {
                port.speak("В контактах нет «${step.who}». Ищу на экране сообщений.")
                draftOnScreen(step)
            }
        }
    }

    private suspend fun openDraft(phone: String, name: String, body: String): Outcome {
        if (!port.showSmsDraft(phone, name, body)) {
            port.speak("Не удалось открыть сообщения")
            return Outcome.Failed
        }
        port.log("Черновик для $name")
        if (!pacer.afterAction() || !pacer.settle(APP_SETTLE_MS)) return abort()
        return Outcome.Continue
    }

    private suspend fun draftOnScreen(step: Step.DraftSms): Outcome {
        val opened = port.openApp("сообщения", emptyList(), AppCatalog.searchTerms("сообщения"))
        if (opened is OpenAppResult.NotFound) {
            port.speak("Приложение сообщений не найдено")
            return Outcome.NotFound
        }
        port.log("Открыты сообщения")
        if (!pacer.afterAction() || !pacer.settle(APP_SETTLE_MS)) return abort()
        when (
            val created = tapQueries(
                queries = listOf("Новое сообщение", "Начать чат", "Написать", "Compose", "Создать"),
                role = FindRole.Clickable,
                scroll = false,
                optional = true,
                sensitiveAllowed = false,
            )
        ) {
            is Outcome.Aborted, is Outcome.Waiting -> return created
            else -> Unit
        }
        when (val typedWho = enter(Step.Enter(step.who, null))) {
            Outcome.Continue -> Unit
            else -> return typedWho
        }
        when (
            val picked = tapQueries(
                queries = listOf(step.who),
                role = FindRole.Clickable,
                scroll = false,
                optional = true,
                sensitiveAllowed = false,
            )
        ) {
            is Outcome.Aborted, is Outcome.Waiting -> return picked
            else -> Unit
        }
        when (
            val field = tapQueries(
                queries = listOf("Сообщение", "Текст сообщения", "SMS", "Написать сообщение"),
                role = FindRole.Editable,
                scroll = false,
                optional = true,
                sensitiveAllowed = false,
            )
        ) {
            is Outcome.Aborted, is Outcome.Waiting -> return field
            else -> Unit
        }
        return enter(Step.Enter(step.body, null))
    }

    private suspend fun dial(step: Step.Dial): Outcome {
        val digits = step.who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3) {
            return openDialer(digits, digits)
        }
        return when (val match = port.resolveContact(step.who)) {
            is ContactMatch.Found -> openDialer(match.contact.phone, match.contact.displayName)
            is ContactMatch.Ambiguous -> {
                val names = match.contacts.take(3).joinToString(", ") { it.displayName }
                port.speak("Несколько контактов: $names. Скажите имя точнее.")
                Outcome.Ambiguous
            }
            ContactMatch.NoPermission -> {
                port.speak("Нет доступа к контактам, поэтому номер «${step.who}» не найден")
                Outcome.Failed
            }
            ContactMatch.NotFound -> {
                port.speak("В контактах нет «${step.who}»")
                Outcome.NotFound
            }
        }
    }

    private suspend fun openDialer(phone: String, label: String): Outcome {
        if (!port.showDialer(phone, label)) {
            port.speak("Не удалось открыть звонилку")
            return Outcome.Failed
        }
        port.log("Номер $label на экране")
        if (!pacer.afterAction() || !pacer.settle(APP_SETTLE_MS)) return abort()
        return Outcome.Continue
    }

    private suspend fun abort(): Outcome {
        port.highlight(null)
        pending = null
        queue = emptyList()
        port.log("Остановлено")
        return Outcome.Aborted
    }

    private companion object {
        const val APP_SETTLE_MS = 700L
    }
}

private fun systemLabel(action: app.autopil.core.model.GlobalAction): String = when (action) {
    app.autopil.core.model.GlobalAction.Back -> "Назад"
    app.autopil.core.model.GlobalAction.Home -> "Домой"
    app.autopil.core.model.GlobalAction.Recents -> "Недавние"
    app.autopil.core.model.GlobalAction.Notifications -> "Уведомления"
    app.autopil.core.model.GlobalAction.QuickSettings -> "Быстрые настройки"
    app.autopil.core.model.GlobalAction.LockScreen -> "Блокировка экрана"
    app.autopil.core.model.GlobalAction.Screenshot -> "Снимок экрана"
}
