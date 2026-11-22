package eu.kanade.tachiyomi.animesource.model

/**
 * Compile-time copy of the app's Hoster (extensions-lib 16), which our extensions-lib lacks.
 *
 * Only ever add this module as `compileOnly`: the app loads extensions child-first, so a
 * packaged copy would shadow the app's class and break every hoster it returns. The
 * constructor and properties must match the app's exactly.
 */
@Suppress("unused")
open class Hoster(
    val hosterUrl: String = "",
    val hosterName: String = "",
    val videoList: List<Video>? = null,
    val internalData: String = "",
    val lazy: Boolean = false,
)
