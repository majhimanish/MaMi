package app.mami.data

/** The moods to check in with. Only the emoji travels; each phone words it itself. */
object Moods {
    data class Mood(val emoji: String, val label: String, val theirs: String, val mine: String, val low: Boolean = false)

    val all = listOf(
        Mood("🥰", "Loved", "feels loved", "feel loved"),
        Mood("😊", "Happy", "is happy", "are happy"),
        Mood("🤩", "Excited", "is excited", "are excited"),
        Mood("😌", "Calm", "feels calm", "feel calm"),
        Mood("😴", "Tired", "is tired", "are tired"),
        Mood("🥺", "Missing you", "misses you", "miss them"),
        Mood("😔", "Low", "is feeling low", "are feeling low", low = true),
        Mood("😤", "Stressed", "is stressed", "are stressed", low = true),
        Mood("🤒", "Unwell", "isn't feeling well", "aren't feeling well", low = true),
    )

    fun of(emoji: String): Mood? = all.firstOrNull { it.emoji == emoji }

    /** "Maya is tired 😴". */
    fun sentence(name: String, emoji: String): String = "$name ${of(emoji)?.theirs ?: "is feeling"} $emoji"

    /** "You are tired 😴". */
    fun mySentence(emoji: String): String = "You ${of(emoji)?.mine ?: "feel"} $emoji"
}
