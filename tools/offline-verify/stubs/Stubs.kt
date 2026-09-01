// Minimal stubs so the real LinuxDroid sources can be typechecked off-Android.
// These mirror only the API surface the compiled sources actually touch.
package android.util
object Log {
    const val VERBOSE = 2; const val DEBUG = 3; const val INFO = 4
    const val WARN = 5; const val ERROR = 6; const val ASSERT = 7
}
