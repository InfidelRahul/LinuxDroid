// Minimal runTest replacement (real dispatchers, no virtual time) so the
// LinuxDroid tests can execute without kotlinx-coroutines-test on the classpath.
package kotlinx.coroutines.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
fun runTest(block: suspend CoroutineScope.() -> Unit) { runBlocking { block() } }
