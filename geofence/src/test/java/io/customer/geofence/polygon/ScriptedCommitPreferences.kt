package io.customer.geofence.polygon

import android.content.SharedPreferences

/**
 * Real preferences with scripted commits, for the write-ahead record's failure paths. The next
 * [failNextCommits] commits report failure, as Android's do when the disk write fails. With
 * [keepFailedEdits] such an edit stays visible, as Android's in-memory map keeps an edit whose write
 * failed; without it the edit is dropped. [beforeNextCommit] runs once, just before the next commit.
 */
internal class ScriptedCommitPreferences(
    private val delegate: SharedPreferences
) : SharedPreferences by delegate {
    var failNextCommits = 0
    var keepFailedEdits = false
    var beforeNextCommit: (() -> Unit)? = null

    override fun edit(): SharedPreferences.Editor = ScriptedEditor(delegate.edit())

    /** Scripts the edits the record store makes, putString and remove; other edits are not scripted. */
    private inner class ScriptedEditor(
        private val editor: SharedPreferences.Editor
    ) : SharedPreferences.Editor by editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            editor.putString(key, value)
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            editor.remove(key)
            return this
        }

        override fun commit(): Boolean {
            val hook = beforeNextCommit
            beforeNextCommit = null
            hook?.invoke()
            if (failNextCommits <= 0) return editor.commit()
            failNextCommits -= 1
            if (keepFailedEdits) editor.commit()
            return false
        }
    }
}
