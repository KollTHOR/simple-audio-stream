package com.example.audiostreamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * The in-app log viewer.
 *
 * Reads [AppLogger]'s in-memory ring (never logcat), renders one row per entry with the message
 * wrapping — so there is no horizontal scrolling — and appends only new rows as they arrive.
 * Filters by level, searches tag/message, and can follow the tail live.
 */
class LogsActivity : AppCompatActivity() {

    private enum class LevelFilter { ALL, INFO, WARN, ERROR }

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private lateinit var searchField: TextInputEditText
    private lateinit var followButton: MaterialButton
    private lateinit var adapter: LogEntryAdapter

    private var levelFilter = LevelFilter.ALL
    private var query: String = ""
    private var follow = true
    private var userScrolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_logs)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        recycler = findViewById(R.id.recycler_logs)
        emptyView = findViewById(R.id.tv_logs_empty)
        countView = findViewById(R.id.tv_logs_count)
        searchField = findViewById(R.id.et_logs_search)
        followButton = findViewById(R.id.btn_logs_follow)

        adapter = LogEntryAdapter { entry -> showEntryDetail(entry) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(false)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                userScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // Scrolling up while following means the user wants to read history: stop following
                // rather than yanking them back to the bottom.
                if (follow && userScrolling && !isAtBottom() && dy != 0) setFollow(false)
            }
        })

        findViewById<ImageButton>(R.id.btn_logs_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_logs_menu).setOnClickListener { showMenu(it) }

        findViewById<ChipGroup>(R.id.chip_group_log_level).setOnCheckedStateChangeListener { _, checkedIds ->
            levelFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_level_info -> LevelFilter.INFO
                R.id.chip_level_warn -> LevelFilter.WARN
                R.id.chip_level_error -> LevelFilter.ERROR
                else -> LevelFilter.ALL
            }
            refresh(force = true)
        }

        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refresh(force = true)
            }
        })

        followButton.setOnClickListener { setFollow(!follow) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppLogger.revision.collect { refresh(force = false) }
            }
        }

        updateFollowButton()
        refresh(force = true)
    }

    // ---- Rendering -------------------------------------------------------------------------

    private fun refresh(force: Boolean) {
        val all = AppLogger.entries()
        val visible = if (force) all.filter { matches(it) } else all.filter { matches(it) }
        adapter.submit(visible, force)
        countView.text = "${visible.size} of ${all.size}"
        emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = getString(if (all.isEmpty()) R.string.logs_empty else R.string.logs_no_matches)
        if (follow) scrollToBottom()
    }

    private fun matches(entry: AppLogger.LogEntry): Boolean {
        val levelOk = when (levelFilter) {
            LevelFilter.ALL -> true
            LevelFilter.INFO -> entry.level != 'V' && entry.level != 'D'
            LevelFilter.WARN -> entry.level == 'W' || entry.level == 'E'
            LevelFilter.ERROR -> entry.level == 'E'
        }
        if (!levelOk) return false
        if (query.isEmpty()) return true
        return entry.tag.contains(query, ignoreCase = true) ||
            entry.message.contains(query, ignoreCase = true)
    }

    private fun isAtBottom(): Boolean {
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastVisibleItemPosition()
        return last >= adapter.itemCount - 2
    }

    private fun scrollToBottom() {
        if (adapter.itemCount == 0) return
        recycler.scrollToPosition(adapter.itemCount - 1)
    }

    private fun setFollow(value: Boolean) {
        follow = value
        updateFollowButton()
        if (follow) scrollToBottom()
    }

    private fun updateFollowButton() {
        followButton.setText(if (follow) R.string.logs_following else R.string.logs_follow)
        followButton.alpha = if (follow) 1f else 0.6f
        followButton.setIconResource(R.drawable.ic_arrow_down)
    }

    // ---- Menu / actions --------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_COPY, 0, R.string.logs_menu_copy)
        popup.menu.add(0, MENU_SHARE, 1, R.string.logs_menu_share)
        popup.menu.add(0, MENU_REPORT, 2, R.string.logs_menu_report)
        popup.menu.add(0, MENU_CLEAR, 3, R.string.logs_menu_clear)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COPY -> { AppLogger.copyReportToClipboard(this); true }
                MENU_SHARE -> { AppLogger.shareReport(this); true }
                MENU_REPORT -> { AppLogger.openIssue(this); true }
                MENU_CLEAR -> { AppLogger.clear(); Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun showEntryDetail(entry: AppLogger.LogEntry) {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_log_entry, null)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(view)
        view.findViewById<TextView>(R.id.bs_log_head).text = entry.line()
        view.findViewById<TextView>(R.id.bs_log_body).text = entry.detail()
        view.findViewById<MaterialButton>(R.id.bs_log_copy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("log entry", entry.detail()))
            Toast.makeText(this, R.string.logs_copied_entry, Toast.LENGTH_SHORT).show()
            sheet.dismiss()
        }
        sheet.show()
    }

    // ---- List ------------------------------------------------------------------------------

    private class LogEntryAdapter(
        private val onEntryClick: (AppLogger.LogEntry) -> Unit
    ) : RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {

        private val items = ArrayList<AppLogger.LogEntry>()

        /**
         * Fast path: when the new list keeps the same prefix (the common case — entries only get
         * appended), insert just the new tail. Falls back to a full refresh when a filter changed.
         */
        fun submit(next: List<AppLogger.LogEntry>, force: Boolean) {
            if (force) {
                items.clear()
                items.addAll(next)
                notifyDataSetChanged()
                return
            }
            val samePrefix = items.size <= next.size &&
                (items.isEmpty() || items[items.size - 1].id == next[items.size - 1].id)
            if (!samePrefix) {
                items.clear()
                items.addAll(next)
                notifyDataSetChanged()
                return
            }
            if (next.size == items.size) return
            val start = items.size
            for (i in start until next.size) items.add(next[i])
            notifyItemRangeInserted(start, next.size - start)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onEntryClick)
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val level: TextView = view.findViewById(R.id.tv_log_level)
            private val head: TextView = view.findViewById(R.id.tv_log_head)
            private val message: TextView = view.findViewById(R.id.tv_log_message)

            fun bind(entry: AppLogger.LogEntry, onClick: (AppLogger.LogEntry) -> Unit) {
                val ctx = itemView.context
                level.text = entry.level.toString()
                level.setTextColor(ContextCompat.getColor(ctx, levelColor(entry.level)))
                val line = entry.line()
                val split = line.indexOf(": ", line.indexOf(entry.tag))
                head.text = if (split > 0) line.substring(0, split) else entry.tag
                message.text = entry.message
                itemView.setOnClickListener { onClick(entry) }
            }

            private fun levelColor(level: Char): Int = when (level) {
                'E' -> R.color.status_red
                'W' -> R.color.status_orange
                'I' -> R.color.status_blue
                else -> R.color.text_hint
            }
        }
    }

    private companion object {
        const val MENU_COPY = 1
        const val MENU_SHARE = 2
        const val MENU_REPORT = 3
        const val MENU_CLEAR = 4
    }
}
