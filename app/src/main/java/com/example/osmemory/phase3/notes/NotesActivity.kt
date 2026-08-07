package com.example.osmemory.phase3.notes

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.osmemory.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3 title-free notes mini-app.
 *
 * Notes always save locally first.  Memory association is an explicit second
 * choice, and an associated note never mutates or deletes its memory silently.
 */
class NotesActivity : AppCompatActivity() {
    private lateinit var store: NotesStore
    private lateinit var memoryGateway: NotesMemoryGateway
    private lateinit var adapter: NotesAdapter

    private lateinit var notesList: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var editorLayout: TextInputLayout
    private lateinit var editor: TextInputEditText
    private lateinit var memoryStatus: TextView
    private lateinit var characterCount: TextView
    private lateinit var newButton: MaterialButton
    private lateinit var deleteButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var progress: View

    private var notes: List<NoteRecord> = emptyList()
    private var selectedNoteId: String? = null
    private var boundContent: String = ""
    private var bindingEditor = false
    private var busy = false
    private var busyStatus: String? = null

    private val currentNote: NoteRecord?
        get() = notes.firstOrNull { it.id == selectedNoteId }

    private val hasUnsavedChanges: Boolean
        get() = currentNote != null && editor.text?.toString().orEmpty() != boundContent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.phase3_notes_activity)

        store = NotesStore(this)
        memoryGateway = NotesMemoryGateway(this)
        bindViews()
        configureList()
        configureActions()

        refreshNotes(savedInstanceState?.getString(STATE_SELECTED_NOTE_ID))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestFinish()
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_NOTE_ID, selectedNoteId)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.notesToolbar)
        toolbar.setNavigationOnClickListener { requestFinish() }
        notesList = findViewById(R.id.notesList)
        emptyView = findViewById(R.id.notesEmpty)
        editorLayout = findViewById(R.id.notesEditorLayout)
        editor = findViewById(R.id.notesEditor)
        memoryStatus = findViewById(R.id.notesMemoryStatus)
        characterCount = findViewById(R.id.notesCharacterCount)
        newButton = findViewById(R.id.notesNewButton)
        deleteButton = findViewById(R.id.notesDeleteButton)
        saveButton = findViewById(R.id.notesSaveButton)
        progress = findViewById(R.id.notesProgress)
    }

    private fun configureList() {
        adapter = NotesAdapter(
            onClick = { note ->
                if (note.id != selectedNoteId) {
                    afterDiscardCheck { selectNote(note.id) }
                }
            },
            onLongClick = { note -> requestDelete(note) }
        )
        notesList.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        notesList.adapter = adapter
    }

    private fun configureActions() {
        newButton.setOnClickListener {
            afterDiscardCheck {
                val note = store.create()
                refreshNotes(note.id)
                editor.requestFocus()
                editor.post { getSystemService<InputMethodManager>()?.showSoftInput(editor, 0) }
            }
        }
        deleteButton.setOnClickListener { currentNote?.let(::requestDelete) }
        saveButton.setOnClickListener { saveCurrent() }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (!bindingEditor) updateControls()
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
    }

    private fun refreshNotes(preferredId: String? = selectedNoteId) {
        notes = store.all()
        selectedNoteId = preferredId?.takeIf { id -> notes.any { it.id == id } }
            ?: notes.firstOrNull()?.id
        adapter.submitList(notes)
        adapter.select(selectedNoteId)
        notesList.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        bindSelectedNote()
    }

    private fun selectNote(id: String) {
        selectedNoteId = id
        adapter.select(id)
        bindSelectedNote()
    }

    private fun bindSelectedNote() {
        val note = currentNote
        bindingEditor = true
        boundContent = note?.content.orEmpty()
        editor.setText(boundContent)
        editor.setSelection(editor.text?.length ?: 0)
        bindingEditor = false
        updateControls()
    }

    private fun updateControls() {
        val note = currentNote
        val length = editor.text?.length ?: 0
        characterCount.text = "$length/$MAX_CONTENT_LENGTH"
        editorLayout.isEnabled = note != null && !busy
        editorLayout.helperText = when {
            note == null -> "新建一条记录后即可输入"
            hasUnsavedChanges -> "有尚未保存的本地修改"
            else -> "本地记录使用 SharedPreferences + JSON 保存"
        }
        newButton.isEnabled = !busy
        deleteButton.isEnabled = note != null && !busy
        saveButton.isEnabled = note != null && !busy && length in 1..MAX_CONTENT_LENGTH
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        memoryStatus.text = when {
            busy && busyStatus != null -> busyStatus
            note == null -> "尚未选择记录"
            !note.isLinked -> "仅保存在本机 · 保存后可关联 OS Memory"
            note.memoryIsCurrent && !hasUnsavedChanges -> "已关联 OS Memory · 内容已同步"
            else -> "已关联 OS Memory · 当前内容待更新"
        }
    }

    private fun saveCurrent() {
        val note = currentNote ?: return
        val content = editor.text?.toString().orEmpty()
        if (content.isBlank()) {
            editorLayout.error = "请输入记录内容"
            return
        }
        if (content.length > MAX_CONTENT_LENGTH) {
            editorLayout.error = "内容不能超过 $MAX_CONTENT_LENGTH 个字符"
            return
        }
        editorLayout.error = null

        val saved = note.copy(content = content, updatedAt = System.currentTimeMillis())
        store.upsert(saved)
        boundContent = content
        refreshNotes(saved.id)
        Toast.makeText(this, "已保存到本机", Toast.LENGTH_SHORT).show()

        when {
            !saved.isLinked -> offerMemoryAssociation(saved)
            !saved.memoryIsCurrent -> offerMemoryUpdate(saved)
            else -> Toast.makeText(this, "关联记忆已是最新内容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun offerMemoryAssociation(note: NoteRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("关联到 OS Memory？")
            .setMessage("关联后，这段文字会立即进入本地记忆数据流，并可被其他已授权应用使用。")
            .setPositiveButton("关联记忆") { _, _ -> collectMemory(note.id) }
            .setNegativeButton("仅保存在记事本", null)
            .show()
    }

    private fun offerMemoryUpdate(note: NoteRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("更新关联记忆？")
            .setMessage("这条记录已关联 OS Memory。是否用刚保存的文字更新对应记忆？")
            .setPositiveButton("更新记忆") { _, _ -> updateMemory(note.id) }
            .setNegativeButton("暂不更新", null)
            .show()
    }

    private fun collectMemory(noteId: String) {
        val note = store.find(noteId) ?: return
        setBusy(true, "正在写入 OS Memory…")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO + NonCancellable) {
                val result = memoryGateway.collect(note.content)
                if (result is NotesMemoryGateway.CollectOutcome.Linked) {
                    store.find(noteId)?.let { latest ->
                        // Do not mark a newer edit as synced with an older request.
                        val linkedHash = if (latest.content == note.content) {
                            note.content.contentHash()
                        } else {
                            latest.linkedContentHash
                        }
                        store.upsert(
                            latest.copy(
                                linkedMemoId = result.memoId,
                                linkedContentHash = linkedHash
                            )
                        )
                    }
                }
                result
            }
            when (outcome) {
                is NotesMemoryGateway.CollectOutcome.Linked -> {
                    Toast.makeText(this@NotesActivity, outcome.message, Toast.LENGTH_LONG).show()
                }

                is NotesMemoryGateway.CollectOutcome.Failed -> {
                    Toast.makeText(
                        this@NotesActivity,
                        "关联失败：${outcome.reason}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            setBusy(false)
            refreshNotes(noteId)
        }
    }

    private fun updateMemory(noteId: String) {
        val note = store.find(noteId) ?: return
        val memoId = note.linkedMemoId ?: return
        setBusy(true, "正在更新关联记忆…")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO + NonCancellable) {
                val result = memoryGateway.update(memoId, note.content)
                when (result) {
                    NotesMemoryGateway.UpdateOutcome.Updated -> {
                        store.find(noteId)?.let { latest ->
                            if (latest.content == note.content) {
                                store.upsert(latest.copy(linkedContentHash = note.content.contentHash()))
                            }
                        }
                    }
                    NotesMemoryGateway.UpdateOutcome.NotFound -> {
                        store.find(noteId)?.let { latest ->
                            store.upsert(latest.copy(linkedMemoId = null, linkedContentHash = null))
                        }
                    }
                    is NotesMemoryGateway.UpdateOutcome.Failed -> Unit
                }
                result
            }
            when (outcome) {
                NotesMemoryGateway.UpdateOutcome.Updated -> {
                    Toast.makeText(this@NotesActivity, "关联记忆已更新", Toast.LENGTH_SHORT).show()
                }

                NotesMemoryGateway.UpdateOutcome.NotFound -> {
                    Toast.makeText(
                        this@NotesActivity,
                        "原关联记忆已不存在，已解除关联；再次保存可重新关联",
                        Toast.LENGTH_LONG
                    ).show()
                }

                is NotesMemoryGateway.UpdateOutcome.Failed -> Toast.makeText(
                    this@NotesActivity,
                    "更新失败：${outcome.reason}",
                    Toast.LENGTH_LONG
                ).show()
            }
            setBusy(false)
            refreshNotes(noteId)
        }
    }

    private fun requestDelete(note: NoteRecord) {
        if (busy) return
        if (note.id == selectedNoteId && hasUnsavedChanges) {
            Toast.makeText(this, "请先保存或放弃当前修改", Toast.LENGTH_SHORT).show()
            return
        }

        if (note.isLinked) {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除这条记录？")
                .setMessage("它已关联 OS Memory。你可以只删除本地记录，或同时删除对应记忆。")
                .setPositiveButton("同时删除记忆") { _, _ -> deleteWithMemory(note) }
                .setNegativeButton("仅删除本地记录") { _, _ -> deleteLocal(note.id) }
                .setNeutralButton("取消", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除这条记录？")
                .setMessage("本地记录删除后无法恢复。")
                .setPositiveButton("删除") { _, _ -> deleteLocal(note.id) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun deleteWithMemory(note: NoteRecord) {
        val memoId = note.linkedMemoId ?: return deleteLocal(note.id)
        setBusy(true, "正在删除关联记忆…")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO + NonCancellable) {
                val result = memoryGateway.delete(memoId)
                if (
                    result == NotesMemoryGateway.DeleteOutcome.Deleted ||
                    result == NotesMemoryGateway.DeleteOutcome.NotFound
                ) {
                    store.delete(note.id)
                }
                result
            }
            when (outcome) {
                NotesMemoryGateway.DeleteOutcome.Deleted -> {
                    refreshNotes()
                    Toast.makeText(this@NotesActivity, "本地记录与关联记忆均已删除", Toast.LENGTH_LONG)
                        .show()
                }

                NotesMemoryGateway.DeleteOutcome.NotFound -> {
                    refreshNotes()
                    Toast.makeText(this@NotesActivity, "关联记忆已不存在，本地记录已删除", Toast.LENGTH_LONG)
                        .show()
                }

                is NotesMemoryGateway.DeleteOutcome.Failed -> Toast.makeText(
                    this@NotesActivity,
                    "未删除：${outcome.reason}。可改选“仅删除本地记录”",
                    Toast.LENGTH_LONG
                ).show()
            }
            setBusy(false)
        }
    }

    private fun deleteLocal(noteId: String) {
        store.delete(noteId)
        refreshNotes()
    }

    private fun setBusy(value: Boolean, status: String? = null) {
        busy = value
        busyStatus = status.takeIf { value }
        updateControls()
    }

    private fun afterDiscardCheck(action: () -> Unit) {
        if (!hasUnsavedChanges) {
            action()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("放弃未保存的修改？")
            .setMessage("切换后，编辑区中尚未保存的文字会丢失。")
            .setPositiveButton("放弃并继续") { _, _ -> action() }
            .setNegativeButton("继续编辑", null)
            .show()
    }

    private fun requestFinish() {
        if (busy) {
            Toast.makeText(this, "记忆操作完成后即可退出", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasUnsavedChanges) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("退出记事本？")
            .setMessage("当前修改尚未保存。")
            .setPositiveButton("放弃并退出") { _, _ -> finish() }
            .setNegativeButton("继续编辑", null)
            .show()
    }

    private companion object {
        const val MAX_CONTENT_LENGTH = 5000
        const val STATE_SELECTED_NOTE_ID = "selectedNoteId"
    }
}
