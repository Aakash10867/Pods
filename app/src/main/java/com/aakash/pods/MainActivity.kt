package com.aakash.pods

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {

    private lateinit var grid: GridLayout
    private lateinit var empty: TextView

    private val palette = intArrayOf(
        Color.parseColor("#3F51B5"), Color.parseColor("#00897B"), Color.parseColor("#E64A19"),
        Color.parseColor("#8E24AA"), Color.parseColor("#1E88E5"), Color.parseColor("#C62828"),
        Color.parseColor("#558B2F"), Color.parseColor("#6D4C41"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7FB"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(8))
        }
        val title = TextView(this).apply {
            text = "Pods"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B1F3B"))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = TextView(this).apply {
            text = "+"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = bg(Color.parseColor("#3F51B5"), oval = true)
            setOnClickListener { showAddDialog() }
        }
        header.addView(add, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid = GridLayout(this).apply {
            columnCount = 2
            setPadding(dp(10), dp(4), dp(10), dp(24))
        }
        empty = TextView(this).apply {
            text = "No sites yet.\nTap + to add WhatsApp Web, Gmail, ChatGPT, Claude, Zerodha or any website. Each one keeps its own separate login."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#5A5F7A"))
            setPadding(dp(32), dp(64), dp(32), dp(32))
        }
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this)
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        grid.removeAllViews()
        val tiles = TileStore.load(this)
        empty.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
        for (t in tiles) {
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(tileView(t), lp)
        }
    }

    private fun tileView(t: Tile): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(18), dp(12), dp(16))
            background = bg(Color.WHITE, radiusDp = 18)
            elevation = dp(2).toFloat()
            isClickable = true
            setOnClickListener { openTile(t) }
            setOnLongClickListener {
                showTileMenu(t)
                true
            }
        }
        val avatar = TextView(this).apply {
            text = t.name.trim().take(1).uppercase().ifEmpty { "?" }
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg(palette[(t.id.hashCode() and 0x7fffffff) % palette.size], oval = true)
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)))

        val name = TextView(this).apply {
            text = t.name
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B1F3B"))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val host = Uri.parse(t.url).host ?: t.url
        val sub = TextView(this).apply {
            text = if (t.locked) "$host  🔒" else host
            textSize = 12f
            setTextColor(Color.parseColor("#7A7F9A"))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun openTile(t: Tile) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            toast("Separate logins need a newer Android System WebView. Update it in the Play Store, then try again.")
            return
        }
        startActivity(
            Intent(this, WebActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("pods://tile/${t.id}")
                putExtra("id", t.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
        )
    }

    private fun showTileMenu(t: Tile) {
        val items = arrayOf(
            "Rename",
            "Change address",
            if (t.locked) "Stop requiring screen lock" else "Require screen lock to open",
            if (t.desktop) "Use mobile site" else "Use desktop site",
            "Delete (erases its login)",
        )
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptText("Rename", t.name) { v ->
                        TileStore.update(this, t.copy(name = v))
                        refresh()
                    }
                    1 -> promptText("Address", t.url) { v ->
                        val u = normalizeUrl(v)
                        if (u == null) {
                            toast("That doesn't look like a web address")
                        } else {
                            TileStore.update(this, t.copy(url = u))
                            refresh()
                        }
                    }
                    2 -> {
                        TileStore.update(this, t.copy(locked = !t.locked))
                        refresh()
                    }
                    3 -> {
                        TileStore.update(this, t.copy(desktop = !t.desktop))
                        refresh()
                    }
                    4 -> confirmDelete(t)
                    else -> {}
                }
            }
            .show()
    }

    private fun confirmDelete(t: Tile) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${t.name}?")
            .setMessage("This signs you out and erases everything saved for this site on this phone.")
            .setPositiveButton("Delete") { _, _ -> deleteTile(t) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteTile(t: Tile) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().deleteProfile(t.profile)
            } catch (e: IllegalStateException) {
                toast("Close ${t.name} first, then delete it.")
                return
            } catch (e: Exception) {
                // The profile may never have been created; nothing to erase.
            }
        }
        TileStore.remove(this, t.id)
        refresh()
    }

    private fun showAddDialog() {
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                TEMPLATES.map { it.label },
            )
        }
        val nameIn = EditText(this).apply {
            hint = "Name, e.g. Gmail - personal"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val urlIn = EditText(this).apply {
            hint = "Address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val desktop = CheckBox(this).apply { text = "Request desktop site" }
        val lock = CheckBox(this).apply { text = "Require screen lock to open" }

        spinner.setSelection(TEMPLATES.lastIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tpl = TEMPLATES[position]
                urlIn.setText(tpl.url)
                desktop.isChecked = tpl.desktop
                lock.isChecked = tpl.lock
                nameIn.setText(if (position == TEMPLATES.lastIndex) "" else tpl.label)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(spinner)
            addView(nameIn)
            addView(urlIn)
            addView(desktop)
            addView(lock)
        }

        AlertDialog.Builder(this)
            .setTitle("Add a site")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val url = normalizeUrl(urlIn.text.toString())
                if (url == null) {
                    toast("That doesn't look like a web address")
                } else {
                    val name = nameIn.text.toString().trim().ifEmpty { Uri.parse(url).host ?: "Site" }
                    TileStore.add(this, Tile(TileStore.newId(), name, url, desktop.isChecked, lock.isChecked))
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) onOk(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        if (s.startsWith("http://")) s = "https://" + s.removePrefix("http://")
        val u = Uri.parse(s)
        if (u.scheme != "https") return null
        val host = u.host
        if (host.isNullOrBlank() || !host.contains('.')) return null
        return s
    }

    private fun bg(color: Int, radiusDp: Int = 0, oval: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
