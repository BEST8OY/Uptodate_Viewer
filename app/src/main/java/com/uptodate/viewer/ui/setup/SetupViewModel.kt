package com.uptodate.viewer.ui.setup

import android.app.Application
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

    private val _isValidating = MutableStateFlow(false)
    val isValidating: StateFlow<Boolean> = _isValidating

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

            if (databaseManager.setDatabaseDirectory(path)) {
                _isConfigured.value = true
            } else {
                _error.value = "Required database files not found"
            }
            _isValidating.value = false
        }
    }
}
