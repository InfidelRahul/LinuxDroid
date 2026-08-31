package com.linuxdroid.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.core.database.EnvironmentMapper
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.diagnostics.DiagnosticsManager
import com.linuxdroid.core.diagnostics.RuntimeLogExporter
import com.linuxdroid.core.model.DiagnosticsReport
import com.linuxdroid.core.model.Environment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsManager: DiagnosticsManager,
    private val logExporter: RuntimeLogExporter,
    private val dao: EnvironmentDao,
) : ViewModel() {

    val environments: StateFlow<List<Environment>> = dao.observeAll()
        .map { entities -> entities.map { EnvironmentMapper.toDomain(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedEnvironmentId = MutableStateFlow<String?>(null)
    val selectedEnvironmentId: StateFlow<String?> = _selectedEnvironmentId.asStateFlow()

    private val _report = MutableStateFlow<DiagnosticsReport?>(null)
    val report: StateFlow<DiagnosticsReport?> = _report.asStateFlow()

    private val _detailedLogs = MutableStateFlow<String?>(null)
    val detailedLogs: StateFlow<String?> = _detailedLogs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            environments.collect { envs ->
                if (_selectedEnvironmentId.value == null && envs.isNotEmpty()) {
                    _selectedEnvironmentId.value = envs.first().id.value
                    refreshDiagnostics()
                }
            }
        }
    }

    fun selectEnvironment(environmentId: String) {
        _selectedEnvironmentId.value = environmentId
        refreshDiagnostics()
    }

    fun refreshDiagnostics() {
        val envId = _selectedEnvironmentId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val entity = dao.getById(envId)
                if (entity != null) {
                    val env = EnvironmentMapper.toDomain(entity)
                    val generatedReport = diagnosticsManager.generateReport(env)
                    _report.value = generatedReport
                    val logs = logExporter.generateDetailedLogReport(env)
                    _detailedLogs.value = logs
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun exportLogs(context: Context) {
        val envId = _selectedEnvironmentId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val entity = dao.getById(envId) ?: return@launch
            val env = EnvironmentMapper.toDomain(entity)
            val shareIntent = logExporter.createShareIntent(context, env)
            shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
        }
    }
}
