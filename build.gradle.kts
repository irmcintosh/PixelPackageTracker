plugins { id("com.android.application") version "8.9.2" apply false; id("org.jetbrains.kotlin.android") version "2.1.20" apply false; id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false; id("org.jetbrains.kotlin.kapt") version "2.1.20" apply false }

if(gradle.startParameter.isConfigurationCacheRequested) throw GradleException(
 "DEVICE SAFETY GUARD: configuration cache is disabled so every invocation checks the task graph."
)

// Fail before any task executes, including when a harmless-looking alias depends on
// an installer or device test. Device selection alone does not prevent UTP cleanup.
gradle.taskGraph.whenReady {
 val buildPrefixes = listOf("assemble", "bundle", "check", "compile", "compress", "copy", "desugar", "dex", "extract", "generate", "java", "kapt", "lint", "map", "merge", "package", "parse", "pre", "process", "strip", "validate", "write")
 val forbidden = allTasks.filter { task ->
  val name = task.name.lowercase()
  val type = task.javaClass.name
  name.startsWith("install") || name.startsWith("uninstall") ||
   name.startsWith("connected") || name.startsWith("device") || name.startsWith("manageddevice") ||
   listOf("DeviceProviderInstrumentTestTask", "ManagedDeviceInstrumentationTestTask", "InstallVariantTask", "UninstallTask").any { type.contains(it) } ||
   (name.endsWith("androidtest") && buildPrefixes.none { name.startsWith(it) })
 }
 if(forbidden.isNotEmpty()) throw GradleException(
  "DEVICE SAFETY GUARD: blocked ${forbidden.joinToString { it.path }}. " +
   "This project forbids Gradle device tests, install and uninstall tasks. " +
   "Use scripts/Build-Safely.ps1 for builds/JVM tests. Instrumentation is permitted only " +
   "on a separately identified disposable emulator using explicit ADB commands; never on the working emulator or physical Pixel. " +
   "Do not disable this guard to work around a failed update."
 )
}
