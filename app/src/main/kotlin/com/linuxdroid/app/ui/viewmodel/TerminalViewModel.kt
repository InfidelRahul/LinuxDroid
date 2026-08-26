package com.linuxdroid.app.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linuxdroid.core.database.EnvironmentMapper
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentState
import com.linuxdroid.core.runtime.RuntimeBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TerminalLine(
    val text: String,
    val isError: Boolean = false,
    val isCommand: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dao: EnvironmentDao,
    private val runtimeBackend: RuntimeBackend,
) : ViewModel() {

    private val log = LinuxDroidLogger(LogSubsystem.APPLICATION)
    val environmentId: String = checkNotNull(savedStateHandle["environmentId"])

    val environment: StateFlow<Environment?> = dao.observeById(environmentId)
        .map { entity -> entity?.let { EnvironmentMapper.toDomain(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _lines = MutableStateFlow<List<TerminalLine>>(listOf(
        TerminalLine("LinuxDroid Terminal initialized.", isCommand = false),
        TerminalLine("Type a command below or tap a quick action.", isCommand = false),
    ))
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun runCommand(rawCommand: String) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return

        val currentEnv = environment.value ?: run {
            _lines.update { it + TerminalLine("Error: Environment not found ($environmentId)", isError = true) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isRunning.value = true
            _lines.update { it + TerminalLine("$ $cmd", isCommand = true) }

            try {
                // Ensure runtime is ready
                if (currentEnv.state != EnvironmentState.RUNNING) {
                    _lines.update { it + TerminalLine("Initializing proot runtime…", isError = false) }
                    runtimeBackend.prepare(currentEnv)
                    runtimeBackend.initialize(currentEnv)
                    runtimeBackend.start(currentEnv)
                    dao.updateState(
                        id = currentEnv.id.value,
                        state = EnvironmentState.RUNNING.name,
                        timestamp = System.currentTimeMillis(),
                        failureMessage = null,
                    )
                }

                val tokens = cmd.split("\\s+".toRegex()).filter { it.isNotBlank() }
                log.info("Running terminal command: $tokens in ${currentEnv.id}")

                val result = runtimeBackend.executeAndWait(
                    environment = currentEnv,
                    command = tokens,
                    workingDirectory = currentEnv.configuration.homeDir.ifBlank { "/root" },
                    timeoutMs = 60_000,
                )

                if (result.stdout.isNotBlank()) {
                    _lines.update { it + TerminalLine(result.stdout.trimEnd(), isError = false) }
                }
                if (result.stderr.isNotBlank()) {
                    _lines.update { it + TerminalLine(result.stderr.trimEnd(), isError = true) }
                }
                if (result.exitCode != 0) {
                    _lines.update { it + TerminalLine("[Process exited with code ${result.exitCode}]", isError = true) }
                }
            } catch (e: Exception) {
                log.error("Command execution error", e)
                _lines.update { it + TerminalLine("Execution error: ${e.message}", isError = true) }
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun clear() {
        _lines.value = listOf(TerminalLine("Terminal cleared.", isCommand = false))
    }
}

