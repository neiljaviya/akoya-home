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

/** Channels arranged for the channel list: by playlist, then by the playlist's own groups. */
class ChannelIndex(val all: List<Channel>, playlists: List<Playlist>) {
    val byId: Map<String, Channel> = all.associateBy { it.id }
    val numberOf: Map<String, Int>
    val groups: List<GroupEntry>
    private val byKey = HashMap<String, List<Channel>>()

    init {
        val numbers = HashMap<String, Int>(all.size * 2)
        all.forEachIndexed { i, c -> numbers[c.id] = i + 1 }
        numberOf = numbers

        val perPlaylist = LinkedHashMap<String, MutableList<Channel>>()
        for (c in all) perPlaylist.getOrPut(c.playlistId) { ArrayList() }.add(c)

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
}
