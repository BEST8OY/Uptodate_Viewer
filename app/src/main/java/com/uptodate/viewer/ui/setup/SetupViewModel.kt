package com.uptodate.viewer.ui.setup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.data.DatabaseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    application: Application,
    private val databaseManager: DatabaseManager
) : AndroidViewModel(application) {

    private val _isConfigured = MutableStateFlow(databaseManager.isConfigured())
    val isConfigured: StateFlow<Boolean> = _isConfigured

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _availableDbs = MutableStateFlow<List<String>>(emptyList())
    val availableDbs: StateFlow<List<String>> = _availableDbs

    private val _missingDbs = MutableStateFlow<List<String>>(emptyList())
    val missingDbs: StateFlow<List<String>> = _missingDbs

    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating

    fun selectDirectory(path: String) {
        if (path.isBlank()) {
            _error.value = "Please enter a directory path"
            return
        }

        viewModelScope.launch {
            _isValidating.value = true
            _error.value = null

            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                _error.value = "Directory not found: $path"
                _isValidating.value = false
                return@launch
            }

            val missing = databaseManager.getMissingDatabases().filter { !File(dir, it).exists() }
            if (missing.isNotEmpty()) {
                _missingDbs.value = missing
                _availableDbs.value = databaseManager.getAvailableDatabases().filter { File(dir, it).exists() }
                _error.value = "Missing ${missing.size} required file(s)"
                _isValidating.value = false
                return@launch
            }

            if (databaseManager.setDatabaseDirectory(path)) {
                _isConfigured.value = true
                _error.value = null
            } else {
                _error.value = "Failed to configure database"
            }
            _isValidating.value = false
        }
    }

    fun checkDirectory(path: String) {
        val dir = File(path)
        if (dir.exists() && dir.isDirectory) {
            _availableDbs.value = databaseManager.getAvailableDatabases().filter { File(dir, it).exists() }
            _missingDbs.value = databaseManager.getMissingDatabases().filter { !File(dir, it).exists() }
        } else {
            _availableDbs.value = emptyList()
            _missingDbs.value = emptyList()
        }
    }

    fun clearError() {
        _error.value = null
    }
}
