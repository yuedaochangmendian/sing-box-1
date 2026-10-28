package io.github.yuedaochangmendian.skybox

import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator

/** Immutable snapshot of one policy group for the home screen; safe to read from any thread. */
class GroupSnapshot(
    val tag: String,
    val type: String,
    val selectable: Boolean,
    val selected: String,
    val items: List<ItemSnapshot>,
) {
    class ItemSnapshot(
        val tag: String,
        val type: String,
        val delay: Int,
        val urlTestTime: Long,
    )
}

/**
 * Converts the libbox group stream into snapshots and merges later per-outbound delay updates.
 * The whole list is replaced on every WriteGroups; WriteOutbounds only patches known items.
 */
object VpnGroups {
    /** Bumped on every change so the UI can re-render cheaply from its 1-second ticker. */
    @Volatile
    var revision = 0L
        private set

    @Volatile
    var groups: List<GroupSnapshot> = emptyList()
        private set

    @JvmStatic
    fun clear() {
        groups = emptyList()
        revision++
    }

    @JvmStatic
    fun replace(iterator: OutboundGroupIterator?) {
        if (iterator == null) return
        val result = ArrayList<GroupSnapshot>()
        while (iterator.hasNext()) {
            val group = iterator.next() ?: continue
            val items = ArrayList<GroupSnapshot.ItemSnapshot>()
            val entries = try {
                group.getItems()
            } catch (ignored: Exception) {
                null
            }
            if (entries != null) {
                while (entries.hasNext()) {
                    val item = entries.next() ?: continue
                    items.add(
                        GroupSnapshot.ItemSnapshot(
                            item.getTag(), item.getType(),
                            item.getURLTestDelay().toInt(), item.getURLTestTime(),
                        ),
                    )
                }
            }
            result.add(GroupSnapshot(group.getTag(), group.getType(), group.getSelectable(), group.getSelected(), items))
        }
        groups = result
        revision++
    }

    @JvmStatic
    fun patchDelays(iterator: OutboundGroupItemIterator?) {
        if (iterator == null) return
        val current = groups
        if (current.isEmpty()) return
        var changed = false
        val patched = ArrayList<GroupSnapshot>(current.size)
        val updates = ArrayList<GroupSnapshot.ItemSnapshot>()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            updates.add(
                GroupSnapshot.ItemSnapshot(
                    item.getTag(), item.getType(),
                    item.getURLTestDelay().toInt(), item.getURLTestTime(),
                ),
            )
        }
        if (updates.isEmpty()) return
        for (group in current) {
            val items = ArrayList<GroupSnapshot.ItemSnapshot>(group.items.size)
            for (item in group.items) {
                val update = updates.firstOrNull { it.tag == item.tag && it.type == item.type }
                if (update != null && (update.delay != item.delay || update.urlTestTime != item.urlTestTime)) {
                    changed = true
                    items.add(update)
                } else {
                    items.add(item)
                }
            }
            patched.add(GroupSnapshot(group.tag, group.type, group.selectable, group.selected, items))
        }
        if (changed) {
            groups = patched
            revision++
        }
    }
}
