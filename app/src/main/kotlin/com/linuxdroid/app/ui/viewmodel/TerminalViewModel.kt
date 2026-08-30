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
import com.linuxdroid.core.runtime.PtySession
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.runtime.TerminalBuffer
import com.linuxdroid.core.runtime.TerminalLineData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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

    private val terminalBuffer = TerminalBuffer(maxScrollbackLines = 2000)
    val lines: StateFlow<List<TerminalLineData>> = terminalBuffer.lines

    private val _isShellActive = MutableStateFlow(false)
    val isShellActive: StateFlow<Boolean> = _isShellActive.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _shellExitCode = MutableStateFlow<Int?>(null)
    val shellExitCode: StateFlow<Int?> = _shellExitCode.asStateFlow()

    private var ptySession: PtySession? = null
    private var readJob: Job? = null
    private var isCtrlActive = false
    private var isAltActive = false

    private var currentRows = 24
    private var currentCols = 80

    init {
        viewModelScope.launch {
            environment.filterNotNull().first { env ->
                startInteractiveShellSession(env)
                true
            }
        }
    }

    /**
     * Initializes and starts the persistent interactive shell in PTY.
     */
    fun startInteractiveShellSession(env: Environment) {
        if (ptySession?.isAlive() == true) return

        viewModelScope.launch(Dispatchers.IO) {
            _isStarting.value = true
            _shellExitCode.value = null

            try {
                // Ensure runtime backend is prepared and started
                if (env.state != EnvironmentState.RUNNING) {
                    terminalBuffer.append("Starting Linux runtime…\r\n".toByteArray(), "Starting Linux runtime…\r\n".length)
                    runtimeBackend.prepare(env)
                    runtimeBackend.initialize(env)
                    runtimeBackend.start(env)
                    dao.updateState(
                        id = env.id.value,
                        state = EnvironmentState.RUNNING.name,
                        timestamp = System.currentTimeMillis(),
                        failureMessage = null,
                    )
                }

                // Close existing session if any
                closeSession()

                val targetShell = env.configuration.shell.ifBlank { "/bin/bash" }
                val shellCommand = listOf(targetShell, "-l")

                val session = runtimeBackend.startInteractiveShell(
                    environment = env,
                    rows = currentRows,
                    cols = currentCols,
                    command = shellCommand
                )
                ptySession = session
                _isShellActive.value = true
                _isStarting.value = false

                log.info("Interactive shell session connected for ${env.id} (pid=${session.pid})")

                // Start continuous IO reader job
                readJob = launch(Dispatchers.IO) {
                    val buffer = ByteArray(4096)
                    while (isActive && session.isAlive()) {
                        val bytesRead = session.read(buffer)
                        if (bytesRead > 0) {
                            terminalBuffer.append(buffer, bytesRead)
                        } else if (bytesRead < 0) {
                            break
                        }
                    }

                    _isShellActive.value = false
                    val exitCode = session.getExitCode() ?: 1
                    _shellExitCode.value = exitCode
                    val exitMsg = "\r\n[Process completed (exit=$exitCode)]\r\n"
                    terminalBuffer.append(exitMsg.toByteArray(), exitMsg.length)
                    log.info("Shell process exited with code $exitCode")
                }
            } catch (e: Exception) {
                log.error("Failed to spawn interactive shell", e)
                _isStarting.value = false
                _isShellActive.value = false
                val errorMsg = "\r\n[Error launching shell: ${e.message}]\r\n"
                terminalBuffer.append(errorMsg.toByteArray(), errorMsg.length)
            }
        }
    }

    /**
     * Sends keyboard input string directly to the active shell stdin.
     */
    fun sendInput(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = ptySession
            if (session?.isAlive() == true) {
                session.write(text)
            }
        }
    }

    /**
     * Sends raw bytes to the active shell stdin.
     */
    fun sendBytes(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = ptySession
            if (session?.isAlive() == true) {
                session.write(bytes)
            }
        }
    }

    /**
     * Sends predefined shortcut command to the current shell session.
     */
    fun runCommand(cmd: String) {
        sendInput("$cmd\n")
    }

    // ─── Control key helpers ────────────────────────────────────────────────────────

    fun sendCtrlC() = sendBytes(byteArrayOf(0x03)) // SIGINT (ETX)
    fun sendCtrlD() = sendBytes(byteArrayOf(0x04)) // EOF (EOT)
    fun sendCtrlL() = sendBytes(byteArrayOf(0x0C)) // Clear (FF)
    fun sendCtrlZ() = sendBytes(byteArrayOf(0x1A)) // Suspend (SUB)
    fun sendCtrlA() = sendBytes(byteArrayOf(0x01)) // Start of line
    fun sendCtrlE() = sendBytes(byteArrayOf(0x05)) // End of line
    fun sendCtrlU() = sendBytes(byteArrayOf(0x15)) // Kill line
    fun sendCtrlW() = sendBytes(byteArrayOf(0x17)) // Word rubout

    fun sendTab() = sendBytes(byteArrayOf(0x09)) // Tab
    fun sendEscape() = sendBytes(byteArrayOf(0x1B)) // ESC
    fun sendBackspace() = sendBytes(byteArrayOf(0x7F)) // DEL / Backspace
    fun sendEnter() = sendBytes(byteArrayOf(0x0D)) // CR / Enter

    fun sendArrowUp() = sendInput("\u001B[A")
    fun sendArrowDown() = sendInput("\u001B[B")
    fun sendArrowRight() = sendInput("\u001B[C")
    fun sendArrowLeft() = sendInput("\u001B[D")

    fun sendHome() = sendInput("\u001B[H")
    fun sendEnd() = sendInput("\u001B[F")
    fun sendPageUp() = sendInput("\u001B[5~")
    fun sendPageDown() = sendInput("\u001B[6~")
    fun sendInsert() = sendInput("\u001B[2~")
    fun sendDelete() = sendInput("\u001B[3~")

    fun sendFunctionKey(num: Int) {
        val seq = when (num) {
            1 -> "\u001BOP"
            2 -> "\u001BOQ"
            3 -> "\u001BOR"
            4 -> "\u001BOS"
            5 -> "\u001B[15~"
            6 -> "\u001B[17~"
            7 -> "\u001B[18~"
            8 -> "\u001B[19~"
            9 -> "\u001B[20~"
            10 -> "\u001B[21~"
            11 -> "\u001B[23~"
            12 -> "\u001B[24~"
            else -> return
        }
        sendInput(seq)
    }

    /**
     * Resizes the terminal PTY window.
     */
    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        currentRows = rows
        currentCols = cols
        viewModelScope.launch(Dispatchers.IO) {
            ptySession?.resize(rows, cols)
        }
    }

    /**
     * Restarts the interactive shell session.
     */
    fun restartShell() {
        val env = environment.value ?: return
        startInteractiveShellSession(env)
    }

    fun clear() {
        terminalBuffer.clear()
        sendCtrlL()
    }

    private fun closeSession() {
        readJob?.cancel()
        readJob = null
        ptySession?.close()
        ptySession = null
        _isShellActive.value = false
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }
}
