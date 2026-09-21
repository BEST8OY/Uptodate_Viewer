package com.clinref.app.ui.setup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.DatabaseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    application: Application,
    private val databaseManager: DatabaseManager
) : AndroidViewModel(application) {

    val isConfigured: StateFlow<Boolean> = databaseManager.isConfiguredFlow

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating.asStateFlow()

    fun selectDirectory(path: String) {
        viewModelScope.launch {
            _isValidating.value = true
            _error.value = null

            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
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

    fun selectDirectoryUri(uri: Uri) {
        viewModelScope.launch {
            _isValidating.value = true
            _error.value = null

            if (!databaseManager.setDatabaseDirectory(uri)) {
                _error.value = "Selected folder does not contain all required database files."
            }
            _isValidating.value = false
        }
    }
}

