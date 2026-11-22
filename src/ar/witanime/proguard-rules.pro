# The app finds the hoster API by name (EpisodeLoader.checkHasHosters), and our
# extensions-lib doesn't declare it, so R8 would otherwise rename these methods and the
# app would silently fall back to loading every server before playing.
-keepclassmembers class eu.kanade.tachiyomi.animeextension.ar.witanime.WitAnime {
    public *** getHosterList(...);
    public *** getVideoList(...);
}
