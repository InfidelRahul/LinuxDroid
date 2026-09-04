package com.linuxdroid.linux.bootstrap

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dynamically resolves the latest active container rootfs image URL and SHA-256 checksum
 * from linuxcontainers.org (or mirrors) to avoid expired timestamp 404 errors.
 */
class DynamicDistributionResolver(
    private val indexUrl: String = "https://images.linuxcontainers.org/meta/1.0/index-system",
    private val baseUrl: String = "https://images.linuxcontainers.org",
) {
    private val log = LinuxDroidLogger(LogSubsystem.BOOTSTRAP)

    suspend fun resolveLatest(
        definition: DistributionDefinition,
        onLog: suspend (String) -> Unit = { _ -> },
    ): DistributionDefinition = withContext(Dispatchers.IO) {
        val distro = definition.distribution
        val arch = definition.architecture

        // Only linuxcontainers-hosted distributions require dynamic index resolution
        if (!definition.source.url.contains("images.linuxcontainers.org")) {
            return@withContext definition
        }

        val targetDistroName = when (distro) {
            Distribution.DEBIAN -> "debian"
            Distribution.KALI -> "kali"
            Distribution.ARCH_LINUX -> "archlinux"
            Distribution.ALPINE -> "alpine"
            Distribution.UBUNTU -> "ubuntu"
        }

        val targetArch = when (arch) {
            Architecture.ARM64 -> "arm64"
        }

        onLog(">>> [DYNAMIC] Querying live distribution index from $indexUrl...")
        log.info("[DYNAMIC_RESOLVER] Fetching index-system from $indexUrl for $targetDistroName $targetArch")

        try {
            val lines = fetchText(indexUrl)
            if (lines.isEmpty()) {
                log.warn("[DYNAMIC_RESOLVER] Empty index received, falling back to static definition")
                onLog(">>> [WARN] Empty index received, using default fallback source")
                return@withContext definition
            }

            // Line format: <distro>;<release>;<arch>;<variant>;<timestamp>;<path>
            val matchingEntries = mutableListOf<IndexEntry>()
            for (line in lines) {
                val parts = line.split(";")
                if (parts.size >= 6) {
                    val name = parts[0].trim()
                    val release = parts[1].trim()
                    val entryArch = parts[2].trim()
                    val variant = parts[3].trim()
                    val timestamp = parts[4].trim()
                    val path = parts[5].trim()

                    if (name.equals(targetDistroName, ignoreCase = true) &&
                        entryArch.equals(targetArch, ignoreCase = true) &&
                        (variant == "default" || variant == "cloud")
                    ) {
                        val matchesRelease = when (distro) {
                            Distribution.DEBIAN -> {
                                if (definition.release.isNotBlank()) {
                                    release.equals(definition.release, ignoreCase = true)
                                } else {
                                    release.equals("bookworm", ignoreCase = true) || release.equals("trixie", ignoreCase = true)
                                }
                            }
                            Distribution.KALI -> release.equals("current", ignoreCase = true)
                            else -> definition.release.isBlank() || release.equals(definition.release, ignoreCase = true)
                        }

                        if (matchesRelease) {
                            matchingEntries.add(IndexEntry(name, release, entryArch, variant, timestamp, path))
                        }
                    }
                }
            }

            if (matchingEntries.isEmpty()) {
                log.warn("[DYNAMIC_RESOLVER] No matching dynamic entry found for $targetDistroName $targetArch in index")
                onLog(">>> [WARN] No dynamic entry found in index, using default fallback source")
                return@withContext definition
            }

            // Prefer 'default' variant over 'cloud', and take the newest timestamp
            val selected = matchingEntries
                .sortedWith(compareByDescending<IndexEntry> { it.variant == "default" }.thenByDescending { it.timestamp })
                .first()

            val cleanPath = if (selected.path.startsWith("/")) selected.path else "/${selected.path}"
            val rootfsUrl = "$baseUrl${cleanPath}rootfs.tar.xz"
            val sumsUrl = "$baseUrl${cleanPath}SHA256SUMS"

            log.info("[DYNAMIC_RESOLVER] Discovered latest $targetDistroName image at $rootfsUrl (timestamp=${selected.timestamp})")
            onLog(">>> [DYNAMIC] Discovered latest image: $rootfsUrl")

            // Fetch live SHA256SUMS for rootfs.tar.xz
            var dynamicChecksum: String? = null
            try {
                val sumsLines = fetchText(sumsUrl)
                for (sLine in sumsLines) {
                    val sParts = sLine.trim().split("\\s+".toRegex())
                    if (sParts.size >= 2 && sParts[1].endsWith("rootfs.tar.xz")) {
                        dynamicChecksum = sParts[0].trim()
                        log.info("[DYNAMIC_RESOLVER] Verified dynamic SHA-256 digest: $dynamicChecksum")
                        onLog(">>> [DYNAMIC] Verified SHA-256 digest: ${dynamicChecksum.take(16)}...")
                        break
                    }
                }
            } catch (sumEx: Exception) {
                log.warn("[DYNAMIC_RESOLVER] Could not fetch dynamic checksum from $sumsUrl: ${sumEx.message}")
            }

            definition.copy(
                source = definition.source.copy(
                    url = rootfsUrl,
                    checksumUrl = sumsUrl,
                    expectedChecksum = dynamicChecksum ?: definition.source.expectedChecksum,
                    checksumAlgorithm = "SHA-256",
                )
            )
        } catch (e: Exception) {
            log.warn("[DYNAMIC_RESOLVER] Dynamic resolution failed: ${e.message}, falling back to static URL", e)
            onLog(">>> [WARN] Dynamic resolution failed (${e.message}), using fallback URL")
            definition
        }
    }

    private data class IndexEntry(
        val distro: String,
        val release: String,
        val arch: String,
        val variant: String,
        val timestamp: String,
        val path: String,
    )

    private fun fetchText(urlStr: String): List<String> {
        var currentUrl = urlStr
        var connection: HttpURLConnection
        var redirectCount = 0
        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LinuxDroid/1.0 (Android; ARM64)")
            }
            connection.connect()
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("HTTP $status redirect without Location header from $currentUrl")
                currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                redirectCount++
                if (redirectCount > 8) throw IOException("Too many redirects: $redirectCount")
                connection.disconnect()
                continue
            }
            if (status !in 200..299) {
                throw IOException("HTTP $status loading $currentUrl")
            }
            break
        }

        return connection.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readLines()
        }
    }
}

