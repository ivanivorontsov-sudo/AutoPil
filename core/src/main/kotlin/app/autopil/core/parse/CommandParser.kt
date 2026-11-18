package app.autopil.core.parse

import app.autopil.core.match.TextNormalizer
import app.autopil.core.model.Direction
import java.util.Locale

sealed class Command {
    data object Stop : Command()
    data object Confirm : Command()
    data object Skip : Command()
    data object Reject : Command()
    data object Back : Command()
    data object Home : Command()
    data object Recents : Command()
    data object Notifications : Command()
    data object QuickSettings : Command()
    data object LockScreen : Command()
    data object Screenshot : Command()
    data object ReadScreen : Command()
    data object Listen : Command()
    data class Scroll(val direction: Direction) : Command()
    data class OpenApp(val name: String) : Command()
    data class Click(val target: String) : Command()
    data class Type(val text: String, val field: String?) : Command()
    data class Call(val who: String) : Command()
    data class Taxi(val destination: String) : Command()
    data class Message(val who: String, val body: String) : Command()
    data class Sequence(val steps: List<Command>) : Command()
    data class Unknown(val heard: String) : Command()
}

class CommandParser {
    fun parse(raw: String): Command {
        val text = stripWake(TextNormalizer.tidy(raw))
        if (text.isBlank()) return Command.Listen
        splitCompound(text)?.let { parts ->
            val parsed = parts.map { parseSingle(it) }
            if (parsed.all { it !is Command.Unknown }) return Command.Sequence(parsed)
            return Command.Unknown(text)
        }
        return parseSingle(text)
    }

    private fun parseSingle(text: String): Command {
        control(text)?.let { return it }
        scroll(text)?.let { return it }
        taxi(text)?.let { return it }
        call(text)?.let { return it }
        message(text)?.let { return it }
        typeInField(text)?.let { return it }
        open(text)?.let { return it }
        click(text)?.let { return it }
        type(text)?.let { return it }
        return Command.Unknown(text)
    }

    private fun control(text: String): Command? {
        return when (TextNormalizer.normalize(text)) {
            "стоп", "остановись", "хватит", "stop" -> Command.Stop
            "подтверди", "подтверждаю", "да", "да подтверждаю", "confirm" -> Command.Confirm
            "дальше", "пропусти", "потом", "не это", "следующее", "skip" -> Command.Skip
            "отмена", "отмени", "не надо", "нет", "cancel" -> Command.Reject
            "назад", "вернись", "back" -> Command.Back
            "домой", "на главный экран", "главный экран", "home" -> Command.Home
            "недавние", "последние приложения", "открытые приложения" -> Command.Recents
            "уведомления", "шторка", "шторку" -> Command.Notifications
            "быстрые настройки" -> Command.QuickSettings
            "заблокируй экран", "заблокируй", "блокировка" -> Command.LockScreen
            "снимок экрана", "скриншот" -> Command.Screenshot
            "прочитай экран", "что на экране", "прочитай что на экране", "read screen" -> Command.ReadScreen
            else -> null
        }
    }

    private fun scroll(text: String): Command? {
        val match = find(SCROLL, text) ?: return null
        val direction = when (TextNormalizer.normalize(match[1])) {
            "вверх", "up" -> Direction.Backward
            "влево", "left" -> Direction.Left
            "вправо", "right" -> Direction.Right
            else -> Direction.Forward
        }
        return Command.Scroll(direction)
    }

    private fun taxi(text: String): Command? {
        for (pattern in TAXI) {
            val match = find(pattern, text) ?: continue
            val destination = match[1].trim().trim('«', '»', '"')
            if (destination.isNotBlank()) return Command.Taxi(destination)
        }
        return null
    }

    private fun call(text: String): Command? {
        val match = find(CALL, text) ?: find(ENGLISH_CALL, text) ?: return null
        val who = match[1].trim()
        if (who.isBlank()) return null
        return Command.Call(who)
    }

    private fun message(text: String): Command? {
        for (pattern in MESSAGE) {
            val match = find(pattern, text) ?: continue
            val who = match[1].trim().trim(',', ':', '«', '»', '"')
            val body = match[2].trim().trim('«', '»', '"')
            if (who.isNotBlank() && body.isNotBlank()) return Command.Message(who, body)
        }
        return null
    }

    private fun typeInField(text: String): Command? {
        find(TYPE_FIELD_QUOTED, text)?.let { return Command.Type(it[2].trim(), it[1].trim()) }
        val plain = find(TYPE_FIELD, text) ?: return null
        return Command.Type(plain[2].trim(), plain[1].trim())
    }

    private fun open(text: String): Command? {
        val match = find(OPEN, text) ?: find(ENGLISH_OPEN, text) ?: return null
        val name = match[1].trim().trim('«', '»', '"')
        if (name.isBlank()) return null
        return Command.OpenApp(name)
    }

    private fun click(text: String): Command? {
        val match = find(CLICK, text) ?: find(ENGLISH_CLICK, text) ?: return null
        val target = match[1].trim().trim('«', '»', '"')
        if (target.isBlank()) return null
        return Command.Click(target)
    }

    private fun type(text: String): Command? {
        val match = find(TYPE, text) ?: return null
        val value = match[1].trim().trim('«', '»', '"')
        if (value.isBlank()) return null
        return Command.Type(value, null)
    }

    private fun stripWake(text: String): String {
        var current = text.trim()
        while (true) {
            val match = WAKE.find(lower(current)) ?: return current.trim()
            if (match.range.first != 0) return current.trim()
            current = current.substring(match.range.last + 1).trim()
        }
    }

    private fun splitCompound(text: String): List<String>? {
        val lowered = lower(text)
        val ranges = COMPOUND.findAll(lowered).map { it.range }.toList()
        if (ranges.isEmpty()) return null
        val parts = mutableListOf<String>()
        var start = 0
        for (range in ranges) {
            parts += text.substring(start, range.first).trim().trim(',', ' ')
            start = range.last + 1
        }
        parts += text.substring(start).trim().trim(',', ' ')
        return parts.filter { it.isNotBlank() }.takeIf { it.size > 1 }
    }

    /**
     * Patterns are lowercase. Group text is sliced from the original so a message
     * keeps the speaker's capitals. Java's CASE_INSENSITIVE flag does not fold Cyrillic.
     */
    private fun find(pattern: Regex, original: String): List<String>? {
        val lowered = lower(original)
        val match = pattern.find(lowered) ?: return null
        return match.groupValues.indices.map { index ->
            val range = match.groups[index]?.range ?: return null
            original.substring(range)
        }
    }

    private fun lower(text: String): String = text.lowercase(Locale.forLanguageTag("ru"))

    private companion object {
        val WAKE = Regex("^(?:автопилот|автопил|autopilot|autopil)[,:]?\\s*")
        const val VERBS =
            "открой|запусти|зайди|нажми|нажать|тапни|выбери|введи|набери|напиши|отправь|позвони|закажи|прокрути|прочитай|смс|поезджай|open|press|tap|click"
        val COMPOUND = Regex(
            "\\s*,?\\s+(?:и|а потом|затем|потом|после этого|and then|then)\\s+(?=(?:$VERBS)(?![\\p{L}\\p{N}_]))",
        )
        val SCROLL = Regex("^прокрути(?:\\s+экран)?\\s+(вниз|вверх|влево|вправо|дальше)$")
        val TAXI = listOf(
            Regex("^(?:закажи\\s+)?(?:такси|яндекс\\s*go|yandex\\s*go|uber|убер)\\s+(?:до|в|на)\\s+(.+)$"),
            Regex("^поезжай\\s+(?:до|в|на)\\s+(.+)$"),
            Regex("^закажи\\s+такси\\s+(.+)$"),
        )
        val CALL = Regex("^позвони(?:\\s+на)?\\s+(.+)$")
        val ENGLISH_CALL = Regex("^call\\s+(.+)$")
        val MESSAGE = listOf(
            Regex("^(?:напиши|отправь(?:\\s+(?:смс|сообщение))?|смс)\\s+(?:для\\s+|контакту\\s+)?(.+?)\\s*,?\\s*(?:что|:)\\s+(.+)$"),
            Regex("^(?:напиши|смс)\\s+(\\S+)\\s+текст\\s+(.+)$"),
        )
        val TYPE_FIELD_QUOTED = Regex("^(?:введи|набери|напиши)\\s+в\\s+поле\\s+(.+?)\\s+(?:текст\\s+)?[«\"](.+?)[»\"]$")
        val TYPE_FIELD = Regex("^(?:введи|набери|напиши)\\s+в\\s+поле\\s+(\\S+)\\s+(.+)$")
        val OPEN = Regex("^(?:открой|запусти|зайди\\s+в)\\s+(.+)$")
        val ENGLISH_OPEN = Regex("^open\\s+(.+)$")
        val CLICK = Regex("^(?:нажми|нажать|тапни|выбери)(?:\\s+на)?(?:\\s+кнопку)?\\s+[«\"]?(.+?)[»\"]?$")
        val ENGLISH_CLICK = Regex("^(?:press|tap|click)\\s+(.+)$")
        val TYPE = Regex("^(?:введи|набери|напиши)(?:\\s+текст)?\\s+[«\"]?(.+?)[»\"]?$")
    }
}
