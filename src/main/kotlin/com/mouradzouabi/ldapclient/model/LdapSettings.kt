package com.mouradzouabi.ldapclient.model

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.XCollection

@Service(Service.Level.APP)
@State(name = "LdapClientSettings", storages = [Storage("ldapClient.xml")])
class LdapSettings : PersistentStateComponent<LdapSettings.SettingsState> {

    class SettingsState {
        @get:XCollection(style = XCollection.Style.v2)
        var connections: MutableList<LdapConnectionConfig> = mutableListOf()
        var showOperationalAttributes: Boolean = false
        var defaultSearchSizeLimit: Int = 1000
        var previewChanges: Boolean = true
        @get:XCollection(style = XCollection.Style.v2)
        var savedSearches: MutableList<SavedSearch> = mutableListOf()
        @get:XCollection(style = XCollection.Style.v2, elementName = "filter")
        var recentFilters: MutableList<String> = mutableListOf()
    }

    private val lock = Any()
    private var state = SettingsState()

    override fun getState(): SettingsState = synchronized(lock) { state }

    override fun loadState(state: SettingsState) {
        synchronized(lock) { this.state = state }
    }

    val connections: List<LdapConnectionConfig>
        get() = synchronized(lock) { state.connections.map { it.copy() } }

    fun find(id: String): LdapConnectionConfig? =
        synchronized(lock) { state.connections.firstOrNull { it.id == id }?.copy() }

    fun addOrUpdate(config: LdapConnectionConfig) {
        synchronized(lock) {
            val existing = state.connections.firstOrNull { it.id == config.id }
            if (existing != null) existing.copyFrom(config) else state.connections.add(config.copy())
        }
        LdapEvents.fire { it.connectionsChanged() }
    }

    fun remove(id: String) {
        synchronized(lock) { state.connections.removeIf { it.id == id } }
        LdapEvents.fire { it.connectionsChanged() }
    }

    /** Moves the connection [id] to position [index] of the list. */
    fun moveConnection(id: String, index: Int) {
        synchronized(lock) {
            val from = state.connections.indexOfFirst { it.id == id }
            if (from < 0) return
            val to = index.coerceIn(0, state.connections.lastIndex)
            if (from == to) return
            state.connections.add(to, state.connections.removeAt(from))
        }
        LdapEvents.fire { it.connectionsChanged() }
    }

    var showOperationalAttributes: Boolean
        get() = synchronized(lock) { state.showOperationalAttributes }
        set(value) {
            synchronized(lock) {
                if (state.showOperationalAttributes == value) return
                state.showOperationalAttributes = value
            }
            LdapEvents.fire { it.operationalAttributesToggled(value) }
        }

    var defaultSearchSizeLimit: Int
        get() = synchronized(lock) { state.defaultSearchSizeLimit }
        set(value) = synchronized(lock) { state.defaultSearchSizeLimit = value }

    var previewChanges: Boolean
        get() = synchronized(lock) { state.previewChanges }
        set(value) = synchronized(lock) { state.previewChanges = value }

    val savedSearches: List<SavedSearch>
        get() = synchronized(lock) { state.savedSearches.map { it.copy() } }

    /** Adds [search] or replaces the saved search with the same name. */
    fun saveSearch(search: SavedSearch) {
        synchronized(lock) {
            state.savedSearches.removeIf { it.name == search.name }
            state.savedSearches.add(search.copy())
            state.savedSearches.sortBy { it.name.lowercase() }
        }
    }

    fun removeSearch(name: String) {
        synchronized(lock) { state.savedSearches.removeIf { it.name == name } }
    }

    val recentFilters: List<String>
        get() = synchronized(lock) { state.recentFilters.toList() }

    fun addRecentFilter(filter: String) {
        if (filter.isBlank()) return
        synchronized(lock) {
            state.recentFilters.remove(filter)
            state.recentFilters.add(0, filter)
            while (state.recentFilters.size > MAX_RECENT_FILTERS) state.recentFilters.removeAt(state.recentFilters.lastIndex)
        }
    }

    companion object {
        const val MAX_RECENT_FILTERS = 20

        fun getInstance(): LdapSettings = service()
    }
}
