package com.mouradzouabi.ldapclient.ui.search

import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.NonEmptyInputValidator
import com.mouradzouabi.ldapclient.model.LdapSettings
import com.mouradzouabi.ldapclient.model.SavedSearch
import java.awt.Component

object SavedSearchPrompt {
    /**
     * Asks for a name and saves [search] under it. Replacing another saved search needs confirmation;
     * [currentName] (the saved search being edited) is replaced silently. Returns the name, or null if cancelled.
     */
    fun save(parent: Component, search: SavedSearch, currentName: String?, suggestedName: String): String? {
        val settings = LdapSettings.getInstance()
        val name = Messages.showInputDialog(parent, "Name:", "Save Search", null, currentName ?: suggestedName, NonEmptyInputValidator())
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (name != currentName && settings.savedSearches.any { it.name == name }) {
            val replace = MessageDialogBuilder.yesNo("Save Search", "A saved search named '$name' already exists. Replace it?")
                .yesText("Replace").noText("Cancel").ask(parent)
            if (!replace) return null
        }
        settings.saveSearch(search.copy().also { it.name = name })
        return name
    }
}
