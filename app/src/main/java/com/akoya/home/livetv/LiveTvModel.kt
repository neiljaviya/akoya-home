package com.akoya.home.livetv

const val GROUP_FAV = "*fav"
const val GROUP_ALL = "*all"

/** One row in the left-hand column of the channel list. */
data class GroupEntry(
    val key: String,
    val label: String,
    val count: Int,
    /** True for a section heading (Hindi, Sports, …): selecting it lists the whole section. */
    val isPlaylist: Boolean = false,
    /** The section this group is filed under. */
    val section: String = "",
    /** A category the user made. */
    val isCustom: Boolean = false,
    val hidden: Boolean = false,
)

/** A category the user made: its own name and its own channel order. */
data class Category(val id: String, val name: String, val channels: List<String> = emptyList())

/** The user's channel management choices, applied when the channel list is arranged. */
data class ChannelPrefs(
    val renames: Map<String, String> = emptyMap(),
    val hiddenChannels: Set<String> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val groupNames: Map<String, String> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val sortAz: Boolean = false,
    /** Groups the user moved to a different section: group key to section name. */
    val groupSections: Map<String, String> = emptyMap(),
    /** Channels to leave out because they don't work (only filled when the user asked for that). */
    val offAir: Set<String> = emptySet(),
)

/**
 * Channels arranged for the channel list: the user's categories, then each playlist and its
 * own groups, with renames, hidden channels and hidden groups applied.
 * Built once per change, off the main thread, and shared by the launcher and the player.
 */
class ChannelIndex(raw: List<Channel>, playlists: List<Playlist>, prefs: ChannelPrefs = ChannelPrefs()) {
    /** Every channel that is shown, in list order. */
    val all: List<Channel>
    val byId: Map<String, Channel>
    val numberOf: Map<String, Int>
    /** Groups shown in the channel list. */
    val groups: List<GroupEntry>
    /** Every playlist group, including hidden ones, for the "manage groups" screen. */
    val allGroups: List<GroupEntry>
    val hasPlaylistRows: Boolean
    val hiddenChannelCount: Int
    private val byKey = HashMap<String, List<Channel>>()
    private val sameChannel = HashMap<String, MutableList<Channel>>()

    private val sectionOfGroup = HashMap<String, String>()

    init {
        val names = playlists.associate { it.id to it.name }
        // section -> group key -> channels, in section order then the provider's order.
        val tree = LinkedHashMap<String, LinkedHashMap<String, MutableList<Channel>>>()
        for (s in ChannelSort.SECTIONS) tree[s] = LinkedHashMap()
        val rawName = HashMap<String, String>()
        val hiddenSizes = HashMap<String, Int>()
        val perPlaylist = HashMap<String, MutableList<Channel>>()
        var hiddenChannels = 0
        for (c0 in raw) {
            if (ChannelSort.isSeparator(c0.name)) continue
            val groupKey = c0.playlistId + "/" + c0.group
            val section = sectionOfGroup.getOrPut(groupKey) {
                prefs.groupSections[groupKey]?.takeIf { it in ChannelSort.SECTIONS } ?: ChannelSort.sectionOf(c0.group, names[c0.playlistId].orEmpty())
            }
            val groupsOfSection = tree.getValue(section)
            val list = groupsOfSection.getOrPut(groupKey) { rawName[groupKey] = c0.group; ArrayList() }
            if (c0.id in prefs.hiddenChannels) { hiddenChannels++; continue }
            if (c0.id in prefs.offAir) continue
            if (groupKey in prefs.hiddenGroups) { hiddenSizes[groupKey] = (hiddenSizes[groupKey] ?: 0) + 1; continue }
            val c = prefs.renames[c0.id]?.let { c0.copy(name = it) } ?: c0
            list += c
            perPlaylist.getOrPut(c.playlistId) { ArrayList() }.add(c)
        }
        if (prefs.sortAz) for (g in tree.values) for (list in g.values) list.sortBy { it.name.lowercase() }
        // Channel numbers and zapping follow the sections: Hindi first, the rest of the world last.
        val shown = ArrayList<Channel>(raw.size)
        for (g in tree.values) for (list in g.values) shown += list
        if (prefs.sortAz) shown.sortBy { it.name.lowercase() }
        all = shown
        hiddenChannelCount = hiddenChannels

        val ids = HashMap<String, Channel>(shown.size * 2)
        val numbers = HashMap<String, Int>(shown.size * 2)
        shown.forEachIndexed { i, c ->
            ids[c.id] = c
            numbers[c.id] = i + 1
            for (k in matchKeys(c)) sameChannel.getOrPut(k) { ArrayList(2) }.add(c)
        }
        byId = ids
        numberOf = numbers
        for ((pid, list) in perPlaylist) byKey["p:$pid"] = list

        val visible = ArrayList<GroupEntry>()
        val everything = ArrayList<GroupEntry>()
        for (cat in prefs.categories) {
            val key = "c:" + cat.id
            val list = cat.channels.mapNotNull { ids[it] }
            byKey[key] = list
            visible += GroupEntry(key, cat.name, list.size, isCustom = true)
        }
        for ((section, perGroup) in tree) {
            if (perGroup.isEmpty()) continue
            val whole = perGroup.values.flatten().let { if (prefs.sortAz) it.sortedBy { c -> c.name.lowercase() } else it }
            val sectionKey = "s:$section"
            byKey[sectionKey] = whole
            sectionOfGroup[sectionKey] = section
            val head = GroupEntry(sectionKey, section, whole.size, isPlaylist = true, section = section)
            if (whole.isNotEmpty()) visible += head
            everything += head
            for ((key, sub) in perGroup) {
                val label = prefs.groupNames[key] ?: ChannelSort.label(rawName[key].orEmpty(), section)
                val hidden = key in prefs.hiddenGroups
                everything += GroupEntry(key, label, if (hidden) hiddenSizes[key] ?: 0 else sub.size, hidden = hidden, section = section)
                if (!hidden && sub.isNotEmpty()) {
                    byKey[key] = sub
                    visible += GroupEntry(key, label, sub.size, section = section)
                }
            }
        }
        groups = visible
        allGroups = everything
        hasPlaylistRows = true
    }

    /** The section a group (or section heading) belongs to; empty for favourites, all and own categories. */
    fun sectionOf(key: String): String = sectionOfGroup[key].orEmpty()

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
