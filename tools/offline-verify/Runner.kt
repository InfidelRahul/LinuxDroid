import java.io.File
import java.util.jar.JarFile

fun main(args: Array<String>) {
    val classesDir = File(args[0])
    val names = classesDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }
        .map { it.relativeTo(classesDir).path.removeSuffix(".class").replace(File.separatorChar, '.') }
        .sorted().toList()

    var pass = 0; var fail = 0
    val failures = mutableListOf<String>()

    for (cn in names) {
        val cls = Class.forName(cn)
        val tests = cls.declaredMethods
            .filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.Test" } }
            .sortedBy { it.name }
        if (tests.isEmpty()) continue
        println("\n== $cn ==")
        for (m in tests) {
            val inst = cls.getDeclaredConstructor().newInstance()
            // honour @Before setup methods
            cls.declaredMethods
                .filter { b -> b.annotations.any { it.annotationClass.qualifiedName == "org.junit.Before" } }
                .forEach { it.isAccessible = true; it.invoke(inst) }
            try {
                m.isAccessible = true
                m.invoke(inst)
                println("  PASS ${m.name}"); pass++
            } catch (e: Throwable) {
                val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                println("  FAIL ${m.name}: ${c::class.simpleName}: ${c.message}")
                c.stackTrace.take(4).forEach { println("        at $it") }
                failures += "$cn.${m.name}"; fail++
            }
        }
    }
    println("\n---------------------------------------------")
    println("TOTAL: ${pass + fail}   PASSED: $pass   FAILED: $fail")
    if (failures.isNotEmpty()) { println("Failing:"); failures.forEach { println("  - $it") } }
    println("---------------------------------------------")
    if (fail > 0) kotlin.system.exitProcess(1)
}
