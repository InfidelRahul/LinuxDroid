package com.linuxdroid.linux.bootstrap

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.ArchiveFormat
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.DistributionCatalog
import com.linuxdroid.core.model.DistributionDefinition
import com.linuxdroid.core.model.DistributionManifest
import com.linuxdroid.core.model.DistributionSource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class DynamicDistributionResolverTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `resolveLatest successfully picks latest timestamp and parses SHA256SUMS for debian`() = runBlocking {
        val sampleIndex = """
            debian;bookworm;arm64;default;20260901_05:24;/images/debian/bookworm/arm64/default/20260901_05:24/
            debian;bookworm;arm64;default;20260904_05:24;/images/debian/bookworm/arm64/default/20260904_05:24/
            debian;bookworm;arm64;cloud;20260904_05:24;/images/debian/bookworm/arm64/cloud/20260904_05:24/
            debian;trixie;arm64;default;20260903_05:24;/images/debian/trixie/arm64/default/20260903_05:24/
            ubuntu;jammy;arm64;default;20260904_07:42;/images/ubuntu/jammy/arm64/default/20260904_07:42/
        """.trimIndent()

        val sampleSha256Sums = """
            aaaa111122223333444455556666777788889999aaaabbbbccccddddeeeeffff  incus.tar.xz
            1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef  rootfs.tar.xz
        """.trimIndent()

        server.createContext("/meta/1.0/index-system") { exchange ->
            val bytes = sampleIndex.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        server.createContext("/images/debian/bookworm/arm64/default/20260904_05:24/SHA256SUMS") { exchange ->
            val bytes = sampleSha256Sums.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val resolver = DynamicDistributionResolver(
            indexUrl = "http://127.0.0.1:$serverPort/meta/1.0/index-system",
            baseUrl = "http://127.0.0.1:$serverPort",
        )

        val baseDef = DistributionCatalog.getDefinition(Distribution.DEBIAN, Architecture.ARM64)
        val logs = mutableListOf<String>()
        val resolved = resolver.resolveLatest(baseDef, onLog = { logs.add(it) })

        assertThat(resolved.source.url).isEqualTo("http://127.0.0.1:$serverPort/images/debian/bookworm/arm64/default/20260904_05:24/rootfs.tar.xz")
        assertThat(resolved.source.expectedChecksum).isEqualTo("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")
        assertThat(logs.any { it.contains("Discovered latest image") }).isTrue()
    }

    @Test
    fun `resolveLatest selects current rolling release for kali`() = runBlocking {
        val sampleIndex = """
            kali;current;arm64;default;20260902_17:14;/images/kali/current/arm64/default/20260902_17:14/
            kali;current;arm64;default;20260904_17:14;/images/kali/current/arm64/default/20260904_17:14/
        """.trimIndent()

        val sampleSha256Sums = """
            cafebabedeadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef  rootfs.tar.xz
        """.trimIndent()

        server.createContext("/meta/1.0/index-system") { exchange ->
            val bytes = sampleIndex.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        server.createContext("/images/kali/current/arm64/default/20260904_17:14/SHA256SUMS") { exchange ->
            val bytes = sampleSha256Sums.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val resolver = DynamicDistributionResolver(
            indexUrl = "http://127.0.0.1:$serverPort/meta/1.0/index-system",
            baseUrl = "http://127.0.0.1:$serverPort",
        )

        val baseDef = DistributionCatalog.getDefinition(Distribution.KALI, Architecture.ARM64)
        val resolved = resolver.resolveLatest(baseDef)

        assertThat(resolved.source.url).isEqualTo("http://127.0.0.1:$serverPort/images/kali/current/arm64/default/20260904_17:14/rootfs.tar.xz")
        assertThat(resolved.source.expectedChecksum).isEqualTo("cafebabedeadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef")
    }

    @Test
    fun `resolveLatest safely falls back to static definition when index fails or is unavailable`() = runBlocking {
        val resolver = DynamicDistributionResolver(
            indexUrl = "http://127.0.0.1:$serverPort/nonexistent/index",
            baseUrl = "http://127.0.0.1:$serverPort",
        )

        val baseDef = DistributionCatalog.getDefinition(Distribution.DEBIAN, Architecture.ARM64)
        val resolved = resolver.resolveLatest(baseDef)

        assertThat(resolved.source.url).isEqualTo(baseDef.source.url)
        assertThat(resolved.source.expectedChecksum).isEqualTo(baseDef.source.expectedChecksum)
    }

    @Test
    fun `resolveLatest ignores non-linuxcontainers source definitions`() = runBlocking {
        val resolver = DynamicDistributionResolver(
            indexUrl = "http://127.0.0.1:$serverPort/meta/1.0/index-system",
            baseUrl = "http://127.0.0.1:$serverPort",
        )

        val customDef = DistributionDefinition(
            id = "custom-distro",
            name = "Custom",
            distribution = Distribution.UBUNTU,
            architecture = Architecture.ARM64,
            release = "jammy",
            source = DistributionSource(
                url = "https://partner-mirror.org/ubuntu/rootfs.tar.gz",
                expectedChecksum = "dummy-checksum",
                checksumAlgorithm = "SHA-256",
                format = ArchiveFormat.TAR_GZ,
            ),
            manifest = DistributionManifest(
                version = "1.0",
                release = "jammy",
                variant = "default",
                defaultShell = "/bin/bash",
            ),
        )

        val resolved = resolver.resolveLatest(customDef)
        assertThat(resolved.source.url).isEqualTo("https://partner-mirror.org/ubuntu/rootfs.tar.gz")
        assertThat(resolved.source.expectedChecksum).isEqualTo("dummy-checksum")
    }
}
