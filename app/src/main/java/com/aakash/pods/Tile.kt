package com.aakash.pods

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One saved site. Each tile owns a private WebView profile named after its id. */
data class Tile(
    val id: String,
    val name: String,
    val url: String,
    val desktop: Boolean,
    val locked: Boolean,
    val keepAlive: Boolean = false,
) {
    val profile: String get() = "t_$id"
}

data class Template(
    val label: String,
    val url: String,
    val desktop: Boolean,
    val lock: Boolean,
    val keep: Boolean = false,
)

val TEMPLATES = listOf(
    Template("WhatsApp Web", "https://web.whatsapp.com", desktop = true, lock = false, keep = true),
    Template("Gmail", "https://mail.google.com", desktop = false, lock = false),
    Template("ChatGPT", "https://chatgpt.com", desktop = false, lock = false),
    Template("Claude", "https://claude.ai", desktop = false, lock = false),
    Template("Zerodha Kite", "https://kite.zerodha.com", desktop = false, lock = true),
    Template("Custom site", "https://", desktop = false, lock = false),
)

object TileStore {
    private const val PREFS = "pods"
    private const val KEY = "tiles"

    fun load(ctx: Context): List<Tile> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<Tile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Tile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    desktop = o.optBoolean("desktop", false),
                    locked = o.optBoolean("locked", false),
                    keepAlive = o.optBoolean("keepAlive", false),
                )
            )
        }
        return out
    }

    private fun save(ctx: Context, tiles: List<Tile>) {
        val arr = JSONArray()
        for (t in tiles) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("url", t.url)
                    .put("desktop", t.desktop)
                    .put("locked", t.locked)
                    .put("keepAlive", t.keepAlive)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, tile: Tile) = save(ctx, load(ctx) + tile)

    fun update(ctx: Context, tile: Tile) =
        save(ctx, load(ctx).map { if (it.id == tile.id) tile else it })

    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filter { it.id != id })

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}
