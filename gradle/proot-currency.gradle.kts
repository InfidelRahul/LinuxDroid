import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

val upstreamProotGitUrl = "https://github.com/InfidelRahul/LinuxDroid_proot.git"

/**
 * Finds the local LinuxDroid_proot repository directory if configured or present as a sibling.
 * For external consumers who only build LinuxDroid without local PRoot source, returns null.
 */
fun findLocalProotDir(): File? {
    // 1. Gradle project property (e.g. -Plinuxdroid.proot.dir=/custom/path)
    val propPath = project.findProperty("linuxdroid.proot.dir")?.toString()
    if (!propPath.isNullOrBlank()) {
        val file = File(propPath)
        if (File(file, ".git").exists()) return file
    }

    // 2. local.properties (e.g. proot.dir=/custom/path or linuxdroid.proot.dir=/custom/path)
    val localPropFile = File(rootProject.projectDir, "local.properties")
    if (localPropFile.exists()) {
        val props = Properties().apply { localPropFile.inputStream().use { load(it) } }
        val customDir = props.getProperty("linuxdroid.proot.dir") ?: props.getProperty("proot.dir")
        if (!customDir.isNullOrBlank()) {
            val file = File(customDir)
            if (File(file, ".git").exists()) return file
        }
    }

    // 3. Environment variable (e.g. LINUXDROID_PROOT_DIR=/custom/path)
    val envPath = System.getenv("LINUXDROID_PROOT_DIR")
    if (!envPath.isNullOrBlank()) {
        val file = File(envPath)
        if (File(file, ".git").exists()) return file
    }

    // 4. Sibling directory relative to the project root (../LinuxDroid_proot)
    val siblingDir = File(rootProject.projectDir.parentFile, "LinuxDroid_proot")
    if (File(siblingDir, ".git").exists()) {
        return siblingDir
    }

    return null
}

/**
 * Executes a shell command with a timeout and returns standard output, or throws/returns null on error.
 */
fun runProcess(vararg command: String, workingDir: File = rootProject.projectDir, timeoutSeconds: Long = 10): Pair<Int, String> {
    return try {
        val process = ProcessBuilder(*command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Pair(-1, "Process timed out after ${timeoutSeconds}s")
        } else {
            Pair(process.exitValue(), output.trim())
        }
    } catch (e: Exception) {
        Pair(-1, e.message ?: "Unknown process execution error")
    }
}

/**
 * Queries the remote repository HEAD commit. Returns null if unreachable (offline/network failure).
 */
fun queryRemoteCommit(repoUrl: String): String? {
    val (exitCode, output) = runProcess("git", "ls-remote", repoUrl, "HEAD", timeoutSeconds = 6)
    if (exitCode == 0 && output.isNotBlank()) {
        val firstLine = output.lineSequence().firstOrNull()?.trim() ?: ""
        val parts = firstLine.split(Regex("\\s+"))
        if (parts.isNotEmpty() && parts[0].length >= 7) {
            return parts[0]
        }
    }
    return null
}

tasks.register("verifyProotCurrency") {
    group = "verification"
    description = "Ensures LinuxDroid consumes the latest PRoot source code from https://github.com/InfidelRahul/LinuxDroid_proot.git, unless building offline."

    doLast {
        val isOfflineParam = gradle.startParameter.isOffline ||
                project.findProperty("linuxdroid.offline")?.toString()?.toBoolean() == true

        if (isOfflineParam) {
            logger.lifecycle("[verifyProotCurrency] Offline build flag detected. Skipping remote PRoot currency check.")
            return@doLast
        }

        logger.lifecycle("[verifyProotCurrency] Checking upstream PRoot repository currency: $upstreamProotGitUrl")
        val remoteCommit = queryRemoteCommit(upstreamProotGitUrl)

        if (remoteCommit == null) {
            logger.warn("[verifyProotCurrency] WARNING: Unable to connect to upstream PRoot repository (network unreachable / offline). Proceeding with existing local state.")
            return@doLast
        }

        logger.lifecycle("[verifyProotCurrency] Upstream PRoot latest remote commit: $remoteCommit")

        // 1. Verify local PRoot repository if present (optional for developers working directly on PRoot source)
        val localProotDir = findLocalProotDir()
        if (localProotDir != null) {
            logger.lifecycle("[verifyProotCurrency] Found local PRoot workspace at: ${localProotDir.absolutePath}")
            val (localExit, localCommit) = runProcess("git", "-C", localProotDir.absolutePath, "rev-parse", "HEAD")
            if (localExit == 0) {
                val shortRemote = if (remoteCommit.length >= 7) remoteCommit.substring(0, 7) else remoteCommit
                val shortLocal = if (localCommit.length >= 7) localCommit.substring(0, 7) else localCommit

                val (isAncestorExit, _) = runProcess("git", "-C", localProotDir.absolutePath, "merge-base", "--is-ancestor", remoteCommit, localCommit)
                val isLocalUpToDateOrAhead = isAncestorExit == 0 || localCommit == remoteCommit || localCommit.startsWith(shortRemote)

                if (!isLocalUpToDateOrAhead) {
                    logger.lifecycle("[verifyProotCurrency] Local PRoot repository ($shortLocal) differs from remote ($shortRemote). Checking if clean to update...")
                    
                    // Check if working directory is clean
                    val (statusExit, statusOut) = runProcess("git", "-C", localProotDir.absolutePath, "status", "--porcelain")
                    if (statusExit == 0 && statusOut.isBlank()) {
                        logger.lifecycle("[verifyProotCurrency] Fast-forwarding local PRoot repository to latest upstream...")
                        val (pullExit, pullOut) = runProcess("git", "-C", localProotDir.absolutePath, "pull", "--ff-only", "origin", "master")
                        if (pullExit == 0) {
                            logger.lifecycle("[verifyProotCurrency] Successfully updated local LinuxDroid_proot to latest upstream.")
                        } else {
                            throw GradleException(
                                "Local PRoot repository at ${localProotDir.absolutePath} is outdated (local: $shortLocal, upstream: $shortRemote) and fast-forward pull failed: $pullOut. " +
                                "Please update LinuxDroid_proot to the latest upstream commit before building."
                            )
                        }
                    } else {
                        throw GradleException(
                            "Local PRoot repository at ${localProotDir.absolutePath} is outdated (local: $shortLocal, upstream: $shortRemote) and has uncommitted/diverged changes. " +
                            "Please synchronize LinuxDroid_proot with upstream ($shortRemote) before building."
                        )
                    }
                } else {
                    logger.lifecycle("[verifyProotCurrency] Local PRoot repository is up-to-date ($shortLocal).")
                }
            }
        }

        // 2. Verify packaged assets manifest if present
        val assetsProotDir = File(rootProject.projectDir, "app/src/main/assets/proot")
        if (assetsProotDir.exists()) {
            val manifestFiles = assetsProotDir.walkTopDown().filter { it.isFile && it.name == "MANIFEST.txt" }.toList()
            for (manifest in manifestFiles) {
                val text = manifest.readText()
                val commitLine = text.lineSequence().firstOrNull { it.trim().startsWith("commit:", ignoreCase = true) }
                if (commitLine != null) {
                    val manifestCommit = commitLine.substringAfter(":").trim()
                    val isValidRemote = manifestCommit.isNotBlank() && (remoteCommit.startsWith(manifestCommit) || manifestCommit.startsWith(remoteCommit.take(manifestCommit.length)))
                    val isValidLocal = localProotDir != null && runProcess("git", "-C", localProotDir.absolutePath, "rev-parse", "HEAD").let { (code, out) ->
                        code == 0 && (out.startsWith(manifestCommit) || manifestCommit.startsWith(out.take(manifestCommit.length)))
                    }
                    if (!isValidRemote && !isValidLocal) {
                        throw GradleException(
                            "Packaged PRoot runtime manifest at ${manifest.relativeTo(rootProject.projectDir).path} " +
                            "references commit '$manifestCommit', but latest upstream commit is '$remoteCommit'. " +
                            "Outdated PRoot runtime artifacts must not be consumed."
                        )
                    }
                }
            }
        }

        logger.lifecycle("[verifyProotCurrency] PRoot currency check PASSED (commit $remoteCommit).")
    }
}
