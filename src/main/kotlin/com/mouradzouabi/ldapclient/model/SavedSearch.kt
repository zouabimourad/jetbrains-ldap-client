package com.mouradzouabi.ldapclient.model

import com.intellij.util.xmlb.annotations.Tag

/** A named search stored in the application settings. */
@Tag("savedSearch")
class SavedSearch {
    var name: String = ""
    var configId: String = ""
    var baseDn: String = ""
    /** SUB, ONE or BASE. */
    var scope: String = "SUB"
    var filter: String = "(objectClass=*)"
    var attributes: String = "*"
    var sizeLimit: Int = 1000
    var includeOperational: Boolean = false

    fun copy(): SavedSearch = SavedSearch().also {
        it.name = name; it.configId = configId; it.baseDn = baseDn; it.scope = scope; it.filter = filter
        it.attributes = attributes; it.sizeLimit = sizeLimit; it.includeOperational = includeOperational
    }
}
