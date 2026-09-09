import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
}

abstract class GitCommitCountValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String = try {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("sh", "-c", "git rev-list --count HEAD")
            standardOutput = output
        }
        output.toString().trim()
    } catch (e: Exception) {
        "0"
    }
}

val commitCount = providers.of(GitCommitCountValueSource::class) {}.get().toIntOrNull() ?: 0

/**
 * the bundled `adb` is a binary in the repo, so it is pinned rather than trusted.
 *
 * A swapped, truncated or wrong-ABI binary otherwise shows up as a process that exits instantly with
 * nothing useful in the log — which is a bad afternoon. This turns it into a build failure naming the
 * file. See androidApp/src/main/jniLibs/README.md and PROVENANCE.md.
 */
/** the sha256 helper both adb tasks need; a base class so neither captures the build script. */
abstract class AdbChecksumTask : DefaultTask() {
    @get:InputFiles
    abstract val binaries: ConfigurableFileCollection

    protected fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    protected fun bundled(): List<File> =
        binaries.files.filter { it.isFile }.sortedBy { it.parentFile.name }
}

/**
 * the bundled `adb` is a binary in the repo, so it is pinned rather than trusted.
 *
 * A swapped, truncated or wrong-ABI binary otherwise shows up as a process that exits instantly with
 * nothing useful in the log — which is a bad afternoon. This turns it into a build failure naming the
 * file. See androidApp/src/main/jniLibs/README.md and PROVENANCE.md.
 */
abstract class VerifyAdbBinary : AdbChecksumTask() {
    @get:InputFiles
    abstract val checksums: ConfigurableFileCollection

    /** a release refuses an unrecorded binary; a debug build only warns, for a local emulator one. */
    @get:Input
    abstract val strict: Property<Boolean>

    @TaskAction
    fun verify() {
        val recorded = readChecksums(checksums.files.firstOrNull())
        val present = bundled()
        if (present.isEmpty()) {
            logger.info("no adb binary bundled; the app will say so at runtime")
            return
        }
        present.forEach { file ->
            val abi = file.parentFile.name
            val actual = sha256(file)
            val expected = recorded[abi]
            when {
                expected == null && strict.get() -> throw GradleException(
                    "no recorded checksum for the bundled adb ($abi). A release must not ship an " +
                        "unpinned binary — run `./gradlew :androidApp:recordAdbChecksums` and " +
                        "record where it came from in src/main/jniLibs/PROVENANCE.md"
                )
                expected == null -> logger.warn(
                    "adb ($abi) has no recorded checksum: $actual — fine for a debug build, but " +
                        "run :androidApp:recordAdbChecksums before shipping it"
                )
                expected != actual -> throw GradleException(
                    "the bundled adb ($abi) is not the one that was recorded.\n" +
                        "  expected $expected\n  actual   $actual\n" +
                        "If you replaced it on purpose, re-run :androidApp:recordAdbChecksums."
                )
                else -> logger.info("adb ($abi) matches its recorded checksum")
            }
        }
    }

    /** `<abi>  <sha256>` per line, `#` starting a comment. */
    private fun readChecksums(file: File?): Map<String, String> {
        if (file == null || !file.exists()) return emptyMap()
        return file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) parts[0] to parts[1].lowercase() else null
            }
            .toMap()
    }
}

/** write the checksums of whatever is bundled now, for [VerifyAdbBinary] to hold them to. */
abstract class RecordAdbChecksums : AdbChecksumTask() {
    @get:OutputFile
    abstract val checksums: RegularFileProperty

    @TaskAction
    fun record() {
        val present = bundled()
        if (present.isEmpty()) {
            throw GradleException("there is no adb binary in src/main/jniLibs to record")
        }
        val lines = buildList {
            add("# sha256 of each bundled adb, verified on every build by :androidApp:verifyAdbBinary.")
            add("# Record where the binary came from in PROVENANCE.md — a checksum pins *which* file")
            add("# ships, not whether it should.")
            present.forEach { file ->
                val sum = sha256(file)
                add("${file.parentFile.name}  $sum")
                logger.lifecycle("recorded ${file.parentFile.name}  $sum")
            }
        }
        checksums.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

// same shape as composeApp's: a release is what the requested tasks say it is. Only used to decide
// whether an unrecorded binary is a warning or a failure
val isAdbCheckStrict = gradle.startParameter.taskNames.joinToString(" ").let { names ->
    names.contains("Release") && !names.contains("Debug")
}

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val adbBinaries = fileTree(jniLibsDir) { include("*/libadb.so") }

val verifyAdbBinary = tasks.register<VerifyAdbBinary>("verifyAdbBinary") {
    group = "verification"
    description = "Check the bundled adb binary against its recorded checksum"
    binaries.from(adbBinaries)
    checksums.from(jniLibsDir.file("checksums.txt"))
    strict.set(isAdbCheckStrict)
}

tasks.register<RecordAdbChecksums>("recordAdbChecksums") {
    group = "build setup"
    description = "Record the sha256 of the bundled adb binary"
    binaries.from(adbBinaries)
    checksums.set(jniLibsDir.file("checksums.txt"))
}



// launcher label — keep in sync with Branding.appName (commonMain)
val brandAppName = "Device Manager"

android {
    namespace = "com.jpage4500.devicemanager.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jpage4500.devicemanager"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = commitCount
        versionName = "1.0.$commitCount"
        // the composeApp library ships no strings.xml, so this is the only source of the label
        resValue("string", "app_name", brandAppName)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // the bundled `adb` binary lives in src/main/jniLibs/<abi>/libadb.so and has to be a real file
    // on disk to be executed. the modern default maps native libraries straight out of the APK and
    // never extracts them, which would leave nativeLibraryDir empty — so legacy packaging is
    // required here, not a preference. see manager/LocalAdbServer.android.kt
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyAdbBinary)
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
}
