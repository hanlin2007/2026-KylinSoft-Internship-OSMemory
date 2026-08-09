package com.example.osmemory.phase3.notes

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.osmemory.R
import com.google.android.material.button.MaterialButton

/** 记事本主页面：只展示记录列表，新建或点击记录后进入独立编辑页。 */
class NotesActivity : AppCompatActivity() {
    private lateinit var store: NotesStore
    private lateinit var adapter: NotesAdapter
    private lateinit var notesList: RecyclerView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.phase3_notes_activity)

        store = NotesStore(this)
        notesList = findViewById(R.id.notesList)
        emptyView = findViewById(R.id.notesEmpty)
        adapter = NotesAdapter { note -> openEditor(note.id) }
        notesList.layoutManager = LinearLayoutManager(this)
        notesList.adapter = adapter

        findViewById<MaterialButton>(R.id.notesNewButton).setOnClickListener {
            openEditor(store.create().id)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotes()
    }

    private fun refreshNotes() {
        val notes = store.all()
        adapter.submitList(notes)
        notesList.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEditor(noteId: String) {
        startActivity(
            Intent(this, NoteEditorActivity::class.java)
                .putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
        )
    }
}
