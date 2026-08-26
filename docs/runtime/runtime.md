# LinuxDroid — Runtime Architecture

## 1. RuntimeBackend Abstraction
LinuxDroid decouples the userspace virtualization mechanism from the core domain:

```kotlin
interface RuntimeBackend {
    val name: String
    suspend fun prepare(environment: Environment)
    suspend fun initialize(environment: Environment)
    suspend fun start(environment: Environment)
    suspend fun stop(environment: Environment)
    suspend fun execute(
        environment: Environment,
        command: List<String>,
        workingDirectory: String = "/home/user",
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessHandle
    suspend fun executeAndWait(
        environment: Environment,
        command: List<String>,
        workingDirectory: String = "/home/user",
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000,
    ): ProcessResult
    suspend fun healthCheck(environment: Environment): Boolean
}
```

This ensures that while PRoot is the initial rootless backend, future backends (e.g. specialized kernel tracing or seccomp runners) can be plugged in without changing domain logic.
