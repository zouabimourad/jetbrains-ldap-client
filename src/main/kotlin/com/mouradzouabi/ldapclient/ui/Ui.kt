package com.mouradzouabi.ldapclient.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/** EDT dispatcher usable while modal dialogs are shown (UI updates only, no write actions). */
val UiDispatcher: CoroutineContext
    get() = Dispatchers.EDT + ModalityState.any().asContextElement()

const val TOOL_WINDOW_ID = "LDAP"
const val RESULTS_TOOL_WINDOW_ID = "LDAP Results"
