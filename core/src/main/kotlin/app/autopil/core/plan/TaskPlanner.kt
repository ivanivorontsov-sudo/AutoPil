package app.autopil.core.plan

import app.autopil.core.apps.AppCatalog
import app.autopil.core.model.FindRole
import app.autopil.core.model.GlobalAction
import app.autopil.core.parse.Command

class TaskPlanner {
    fun plan(command: Command): List<Step> = when (command) {
        is Command.Sequence -> command.steps.flatMap { plan(it) }
        else -> listOf(Step.BeginTask) + stepsFor(command)
    }

    private fun stepsFor(command: Command): List<Step> = when (command) {
        is Command.Sequence -> emptyList()
        Command.Stop, Command.Confirm, Command.Skip, Command.Reject -> emptyList()
        Command.Listen -> listOf(Step.Say("Слушаю"))
        Command.Back -> listOf(Step.System(GlobalAction.Back))
        Command.Home -> listOf(Step.System(GlobalAction.Home))
        Command.Recents -> listOf(Step.System(GlobalAction.Recents))
        Command.Notifications -> listOf(Step.System(GlobalAction.Notifications))
        Command.QuickSettings -> listOf(Step.System(GlobalAction.QuickSettings))
        Command.LockScreen -> listOf(Step.System(GlobalAction.LockScreen))
        Command.Screenshot -> listOf(Step.System(GlobalAction.Screenshot))
        Command.ReadScreen -> listOf(Step.AnnounceScreen)
        is Command.Scroll -> listOf(Step.ScrollTo(command.direction))
        is Command.OpenApp -> listOf(
            Step.Say("Открываю ${command.name}"),
            Step.Launch(command.name, emptyList(), AppCatalog.searchTerms(command.name)),
        )
        is Command.Click -> listOf(Step.Tap(command.target))
        is Command.Type -> listOf(Step.Enter(command.text, command.field))
        is Command.Call -> listOf(
            Step.Say("Открываю набор номера для ${command.who}"),
            Step.Dial(command.who),
            Step.Gate(
                prompt = "Номер на экране. Скажите «подтверди», чтобы нажать «Позвонить».",
                queries = listOf("Позвонить", "Call", "Набрать"),
            ),
        )
        is Command.Taxi -> taxi(command.destination)
        is Command.Message -> listOf(
            Step.Say("Готовлю сообщение для ${command.who}"),
            Step.DraftSms(command.who, command.body),
            Step.Gate(
                prompt = "Сообщение для «${command.who}» заполнено. Скажите «подтверди», чтобы нажать «Отправить».",
                queries = listOf("Отправить", "Send", "Отправить сообщение"),
            ),
        )
        is Command.Unknown -> listOf(
            Step.Say("Команда не распознана. Можно сказать: открой сообщения, нажми поиск, закажи такси до поликлиники."),
        )
    }

    private fun taxi(destination: String): List<Step> = listOf(
        Step.Say("Открываю такси. Адрес: $destination"),
        Step.Launch("такси", AppCatalog.taxiPackages, AppCatalog.searchTerms("такси")),
        Step.TapFirst(DESTINATION_HINTS, FindRole.Editable),
        Step.Enter(destination, null),
        Step.Say("Выбираю подсказку"),
        Step.Tap(destination, FindRole.Clickable, scrollIfMissing = false, optional = true),
        Step.Gate(
            prompt = "Маршрут «$destination» готов. Скажите «подтверди», чтобы нажать кнопку заказа. Оплату отдельно не подтверждаю.",
            queries = listOf("Заказать", "Вызвать", "Order", "Подтвердить заказ", "Поехали"),
        ),
    )

    companion object {
        val DESTINATION_HINTS = listOf(
            "Куда едем",
            "Куда поедете",
            "Куда",
            "Where to",
            "Пункт назначения",
            "Адрес",
            "Поиск",
        )
    }
}
