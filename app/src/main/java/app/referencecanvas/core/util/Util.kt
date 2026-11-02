package app.referencecanvas.core.util

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface Clock {
    fun now(): Long
}

@Singleton
class WallClock @Inject constructor() : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

fun interface IdGenerator {
    fun newId(): String
}

@Singleton
class UuidGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** Parses a stored enum name, falling back safely if the value is missing or unknown. */
inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

/** Escapes LIKE wildcards so user input is matched literally (pair with ESCAPE '\'). */
fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
