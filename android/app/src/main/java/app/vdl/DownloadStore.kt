package app.vdl

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/** Liste des téléchargements (file d'attente + historique), persistée en JSON. */
object DownloadStore {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items
    private var file: File? = null
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        file = File(ctx.filesDir, "downloads.json")
        _items.value = runCatching {
            val arr = JSONArray(file!!.readText())
            (0 until arr.length()).map { DownloadItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        loaded = true
    }

    fun get(id: String): DownloadItem? = _items.value.find { it.id == id }

    @Synchronized
    fun add(item: DownloadItem) {
        _items.value = listOf(item) + _items.value
        save()
    }

    /** [persist] = false pour les mises à jour de progression (fréquentes). */
    @Synchronized
    fun update(id: String, persist: Boolean = true, change: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) change(it) else it }
        if (persist) save()
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun clearFinished() {
        _items.value = _items.value.filter { it.status.active }
        save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(DownloadItem.listToJson(_items.value).toString())
            tmp.renameTo(f)
        }
    }
}
