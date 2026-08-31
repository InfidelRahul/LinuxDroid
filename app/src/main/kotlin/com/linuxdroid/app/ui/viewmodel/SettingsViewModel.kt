package com.linuxdroid.app.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.core.storage.AndroidStorageManager
import com.linuxdroid.core.storage.StorageAuthorizationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val storageManager: AndroidStorageManager,
) : ViewModel() {

    val authorizationState: StateFlow<StorageAuthorizationState> = storageManager.authorizationState
    val sharedDirPath: String = storageManager.sharedDirectory.absolutePath

    init {
        checkStorageAccess()
    }

    fun checkStorageAccess() {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.verifyAccess()
        }
    }

    fun getPermissionIntent(): Intent? = storageManager.createPermissionIntent()

    fun hasPromptedStorageAccess(): Boolean = storageManager.hasPromptedStorageAccess()

    fun setPromptedStorageAccess(prompted: Boolean) {
        storageManager.setPromptedStorageAccess(prompted)
    }
}
