package com.akoya.home.livetv

const val GROUP_FAV = "*fav"
const val GROUP_ALL = "*all"

/** One row in the left-hand column of the channel list. */
data class GroupEntry(
    val key: String,
    val label: String,
    val count: Int,
    /** True for a whole playlist (shown as a heading you can still select). */
    val isPlaylist: Boolean = false,
)

/**
 * Channels arranged for the channel list: by playlist, then by the playlist's own groups.
 * Built once per playlist change, off the main thread, and shared by the launcher and the player.
 */
class ChannelIndex(val all: List<Channel>, playlists: List<Playlist>) {
    val byId: Map<String, Channel>
    val numberOf: Map<String, Int>
    val groups: List<GroupEntry>
    val hasPlaylistRows: Boolean
    private val byKey = HashMap<String, List<Channel>>()
    private val sameChannel = HashMap<String, MutableList<Channel>>()

    init {
        val ids = HashMap<String, Channel>(all.size * 2)
        val numbers = HashMap<String, Int>(all.size * 2)
        val perPlaylist = LinkedHashMap<String, MutableList<Channel>>()
        all.forEachIndexed { i, c ->
            ids[c.id] = c
            numbers[c.id] = i + 1
            perPlaylist.getOrPut(c.playlistId) { ArrayList() }.add(c)
            for (k in matchKeys(c)) sameChannel.getOrPut(k) { ArrayList(2) }.add(c)
        }
        byId = ids
        numberOf = numbers

        val entries = ArrayList<GroupEntry>()
        val names = playlists.associate { it.id to it.name }
        val several = perPlaylist.size > 1
        for ((pid, list) in perPlaylist) {
            if (several) {
                val key = "p:$pid"
                byKey[key] = list
                entries += GroupEntry(key, names[pid] ?: "Playlist", list.size, isPlaylist = true)
            }
            val perGroup = LinkedHashMap<String, MutableList<Channel>>()
            for (c in list) perGroup.getOrPut(c.group) { ArrayList() }.add(c)
            if (perGroup.size > 1) {
                for ((g, sub) in perGroup) {
                    val key = "$pid/$g"
                    byKey[key] = sub
                    entries += GroupEntry(key, g.ifBlank { "Other" }, sub.size)
                }
            }
        }
        groups = entries
        hasPlaylistRows = several
    }

    fun hasGroup(key: String) = key == GROUP_FAV || key == GROUP_ALL || key in byKey

    fun channels(key: String, favorites: List<Channel>): List<Channel> = when (key) {
        GROUP_FAV -> favorites
        GROUP_ALL -> all
        else -> byKey[key].orEmpty()
    }

    fun label(key: String): String = when (key) {
        GROUP_FAV -> "Favourites"
        GROUP_ALL -> "All channels"
        else -> groups.firstOrNull { it.key == key }?.label ?: ""
    }

    /**
     * Other links for the same channel (another quality, or the same channel in another
     * playlist), used as backups when the chosen link doesn't play.
     */
    fun alternates(ch: Channel, limit: Int = 4): List<Channel> {
        val out = ArrayList<Channel>()
        val urls = hashSetOf(ch.url)
        for (k in matchKeys(ch)) {
            for (other in sameChannel[k].orEmpty()) {
                if (urls.add(other.url)) out += other
                if (out.size >= limit) return out
            }
        }
        return out
    }

    companion object {
        private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*]""")
        private val COUNTRY_PREFIX = Regex("""^\s*[a-z]{2,3}\s*[:|]\s*""")
        private val QUALITY = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "576p", "480p", "360p")

        /** "Aaj Tak HD (1080p) [Geo-blocked]" and "aaj tak" both become "aajtak". */
        fun nameKey(name: String): String {
            // Providers often prefix a country code: "IN: Aaj Tak", "US | CNN".
            val plain = BRACKETS.replace(COUNTRY_PREFIX.replace(name.lowercase(), ""), " ")
            val sb = StringBuilder()
            for (token in plain.split(' ', '\t', '-', '_', '|', ':', '.', ',')) {
                val t = token.filter { it.isLetterOrDigit() || it == '&' || it == '+' }.trimEnd('+')
                if (t.isNotEmpty() && t !in QUALITY) sb.append(t)
            }
            return sb.toString()
        }

        private fun matchKeys(c: Channel): List<String> {
            val keys = ArrayList<String>(2)
            val n = nameKey(c.name)
            if (n.length >= 3) keys += "n:$n"
            val t = c.tvgId.substringBefore('@').lowercase()
            if (t.length >= 3) keys += "t:$t"
            return keys
        }
    }
}
