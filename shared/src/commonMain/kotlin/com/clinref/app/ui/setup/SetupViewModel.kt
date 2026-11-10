package com.clinref.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.DatabaseManager
import com.clinref.shared.platform.PlatformFileSystem
import com.clinref.shared.platform.getPlatformFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SetupViewModel(
    private val databaseManager: DatabaseManager,
    private val fileSystem: PlatformFileSystem = getPlatformFileSystem()
) : ViewModel() {

    val isConfigured: StateFlow<Boolean> = databaseManager.isConfiguredFlow

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating.asStateFlow()

    fun selectDirectory(path: String) {
        viewModelScope.launch {
            _isValidating.value = true
            _error.value = null

            if (!fileSystem.exists(path) || !fileSystem.isDirectory(path)) {
                _error.value = "Database folder not found at:\n$path"
                _isValidating.value = false
                return@launch
            }

            if (!databaseManager.setDatabaseDirectory(path)) {
                _error.value = "Required database files not found at:\n$path"
            }
            _isValidating.value = false
        }
    }
}
