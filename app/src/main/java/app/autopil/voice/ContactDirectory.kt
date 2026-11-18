package app.autopil.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.autopil.core.apps.NameForms
import app.autopil.core.match.TextNormalizer
import app.autopil.core.model.Contact
import app.autopil.core.model.ContactMatch

internal class ContactDirectory(private val context: Context) {
    fun resolve(spokenName: String): ContactMatch {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        if (allowed != PackageManager.PERMISSION_GRANTED) return ContactMatch.NoPermission
        val scored = mutableListOf<Scored>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val primaryIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)
                val typeIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val number = cursor.getString(numberIndex) ?: continue
                    var score = NameForms.score(spokenName, name)
                    if (score < 70) continue
                    if (primaryIndex >= 0 && cursor.getInt(primaryIndex) == 1) score += 5
                    if (typeIndex >= 0 && cursor.getInt(typeIndex) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                        score += 2
                    }
                    scored += Scored(score, Contact(name, number))
                }
            }
        } catch (_: SecurityException) {
            return ContactMatch.NoPermission
        }
        if (scored.isEmpty()) return ContactMatch.NotFound
        val best = scored.maxOf { it.score }
        val close = scored.filter { best - it.score <= 5 }.map { it.contact }
        val names = close.distinctBy { TextNormalizer.normalize(it.displayName) }
        if (names.size > 1) return ContactMatch.Ambiguous(names.take(4))
        return ContactMatch.Found(close.first())
    }

    private data class Scored(val score: Int, val contact: Contact)
}
