package com.proggerx.shakeclock

import android.content.Context
import java.io.File

class AnimationStore(context: Context) {

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIR)
    private val prefs = appContext.getSharedPreferences(ToySettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val cache = HashMap<String, List<GlyphAnimation.Frame>>()

    data class Entry(val id: String, val name: String, val frameCount: Int, val selected: Boolean)

    fun entries(): List<Entry> {
        directory.mkdirs()
        val selected = selectedIds()
        return (directory.listFiles { file -> file.extension == "json" } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .map { file ->
                val id = file.nameWithoutExtension
                Entry(id, id, frames(id).size, id in selected)
            }
    }

    fun selectedEntries(): List<Entry> = entries().filter { it.selected }

    fun frames(id: String): List<GlyphAnimation.Frame> = cache.getOrPut(id) {
        val file = File(directory, "$id.json")
        if (!file.exists()) emptyList() else GlyphAnimation.parse(file.readText()).orEmpty()
    }

    fun import(displayName: String, source: String): Entry? {
        val parsed = GlyphAnimation.parse(source) ?: return null
        directory.mkdirs()
        val base = displayName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .trim()
            .ifBlank { "animation" }
        var id = base
        var suffix = 2
        while (File(directory, "$id.json").exists()) {
            id = "$base-$suffix"
            suffix++
        }
        File(directory, "$id.json").writeText(source)
        cache[id] = parsed
        setSelected(id, true)
        return Entry(id, id, parsed.size, true)
    }

    fun setSelected(id: String, selected: Boolean) {
        val ids = selectedIds()
        if (selected) ids.add(id) else ids.remove(id)
        prefs.edit().putStringSet(KEY_SELECTED, ids).apply()
    }

    fun remove(id: String) {
        File(directory, "$id.json").delete()
        cache.remove(id)
        val ids = selectedIds()
        ids.remove(id)
        prefs.edit().putStringSet(KEY_SELECTED, ids).apply()
    }

    fun removeAll() {
        directory.listFiles()?.forEach { it.delete() }
        cache.clear()
        prefs.edit().remove(KEY_SELECTED).apply()
    }

    private fun selectedIds(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_SELECTED, emptySet()).orEmpty())

    private companion object {
        const val DIR = "animations"
        const val KEY_SELECTED = "glyph_animation_selected"
    }
}
