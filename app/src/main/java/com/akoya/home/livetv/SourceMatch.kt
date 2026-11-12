package com.akoya.home.livetv

/**
 * Finds, for channels the user arranged under an old provider, the same channels in a new
 * provider's list. Guide id first (the most reliable), then the exact name, then the name
 * with quality tags ignored, preferring a channel in a group of the same name.
 */
object SourceMatch {
    /** @return old channel id to new channel id, for every [wanted] id that has a match. */
    fun map(old: List<Channel>, fresh: List<Channel>, wanted: Set<String>): Map<String, String> {
        if (wanted.isEmpty() || fresh.isEmpty()) return emptyMap()
        val oldById = HashMap<String, Channel>()
        for (c in old) if (c.id in wanted) oldById[c.id] = c
        val byTvg = HashMap<String, Channel>()
        val byExact = HashMap<String, MutableList<Channel>>()
        val byLoose = HashMap<String, MutableList<Channel>>()
        for (c in fresh) {
            if (c.tvgId.isNotBlank()) byTvg.putIfAbsent(c.tvgId.lowercase(), c)
            byExact.getOrPut(c.name.trim().lowercase()) { ArrayList(1) }.add(c)
            ChannelIndex.nameKey(c.name).let { if (it.length >= 3) byLoose.getOrPut(it) { ArrayList(1) }.add(c) }
        }
        val out = HashMap<String, String>()
        for (id in wanted) {
            val o = oldById[id] ?: continue
            fun pick(list: List<Channel>?): Channel? = list?.firstOrNull { it.group.equals(o.group, ignoreCase = true) } ?: list?.firstOrNull()
            val hit = (if (o.tvgId.isNotBlank()) byTvg[o.tvgId.lowercase()] else null)
                ?: pick(byExact[o.name.trim().lowercase()])
                ?: pick(byLoose[ChannelIndex.nameKey(o.name)])
            if (hit != null) out[id] = hit.id
        }
        return out
    }
}
