package com.uptodate.viewer.ui.setup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
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

    fun selectDirectory(path: String) {
        viewModelScope.launch {
            if (databaseManager.setDatabaseDirectory(path)) {
                _isConfigured.value = true
                _error.value = null
                _availableDbs.value = databaseManager.getAvailableDatabases()
                _missingDbs.value = databaseManager.getMissingDatabases()
            } else {
                _error.value = "Invalid directory or missing database files"
            }
        }
    }

    fun selectDirectoryFromUri(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val path = getPathFromUri(uri)
            if (path != null) {
                selectDirectory(path)
            } else {
                _error.value = "Could not resolve directory path"
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val context = getApplication<Application>()
        // Try direct path first
        val file = File(uri.path ?: return null)
        if (file.exists() && file.isDirectory) {
            return file.absolutePath
        }
        // Try parent directory
        return file.parentFile?.absolutePath
    }

    fun clearError() {
        _error.value = null
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
}
