package com.xingmu.tv.search

import android.content.Context

/** 最近 10 条搜索词（长按删除在 UI 侧实现）。 */
class SearchHistory(context: Context) {

    private val prefs = context.getSharedPreferences("tvbox_search", Context.MODE_PRIVATE)

    fun list(): List<String> = prefs.getString(KEY, "").orEmpty()
        .split('\n')
        .filter { it.isNotBlank() }
        .take(MAX)

    fun add(word: String) {
        val text = word.trim()
        if (text.isEmpty()) return
        val next = (listOf(text) + list().filterNot { it == text }).take(MAX)
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    fun remove(word: String) {
        val next = list().filterNot { it == word }
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "history"
        const val MAX = 10
    }
}
