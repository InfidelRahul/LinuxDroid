// Minimal JUnit4 + Truth + TemporaryFolder compatible surface, so the real
// LinuxDroid test sources can be compiled and EXECUTED without network access.
package org.junit
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Test
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Before
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class After
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME) annotation class Rule
