import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

// first value found for any of the given keys: environment variable first, then
// ~/.gradle/gradle.properties. missing resolves to "" rather than failing the build — the log
// upload is optional and a checkout with no keys still has to compile
fun getValue(vararg keys: String): String {
    for (key in keys) {
        val value = System.getenv(key) ?: readGradleProperty(key)
        if (!value.isNullOrBlank()) return value
    }
    return ""
}

fun readGradleProperty(key: String): String? {
    val homeDir = System.getProperty("user.home") ?: return null
    val gradleProps = File(homeDir, ".gradle/gradle.properties")
    if (!gradleProps.exists()) return null
    return runCatching {
        Properties().apply { gradleProps.inputStream().use { load(it) } }.getProperty(key)?.trim()
    }.getOrNull()
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

val commitCount = providers.of(GitCommitCountValueSource::class) {}.get()
val appVersion = "1.0.$commitCount"

// true only for a build that actually ships: the jDeploy fat jar, an android release variant, or an
// Xcode Release framework. everything else is a local run, which is what PlatformUtils.isDebugBuild
// reports — the gate for anything that must never reach a shipped build
val isReleaseBuild = run {
    val taskNames = gradle.startParameter.taskNames.joinToString(" ")
    val isJDeploy = taskNames.contains("buildExecutableJar", ignoreCase = true)
    val isAndroidShip = taskNames.contains("Release", ignoreCase = false) &&
        !taskNames.contains("Debug", ignoreCase = false)
    val isXcodeRelease = System.getenv("CONFIGURATION") == "Release"
    isJDeploy || isAndroidShip || isXcodeRelease
}

// keep in sync with Branding.appName (commonMain), which supplies the in-app title
val brandAppName = "Android Device Manager"
val brandBundleId = "com.jpage4500.devicemanager"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.buildconfig)
}

buildConfig {
    packageName("com.jpage4500.devicemanager")
    buildConfigField("APP_VERSION", appVersion)
    buildConfigField("BUILD_DATE", SimpleDateFormat("yyyy.MM.dd hh:mm a").format(Date()))
    buildConfigField("IS_RELEASE", isReleaseBuild)

    // supabase log upload — where LogManager.uploadLogs() POSTs. one project serves every app, so
    // the keys carry no app prefix. missing resolves to "" and uploading is simply refused
    buildConfigField("LOGGING_URL", getValue("LOGGING_URL"))
    buildConfigField("LOGGING_API", getValue("LOGGING_API"))

    useKotlinOutput {
        topLevelConstants = true
        internalVisibility = false
    }
}

// every end-user string lives in commonMain/composeResources/values/strings.xml; this is what names
// the generated accessor `com.jpage4500.devicemanager.resources.Res` rather than a path-derived one
compose.resources {
    publicResClass = true
    packageOfResClass = "com.jpage4500.devicemanager.resources"
    generateResClass = auto
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    val xcf = XCFramework("ComposeApp")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += "-Xbinary=bundleId=$brandBundleId"
            xcf.add(this)
        }
        iosTarget.compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        // the same main class `compose.desktop.application` runs, repeated here for the KMP
        // plugin's own `desktopRun` task — that's the one IntelliJ generates from the green arrow
        // next to main(), and without a main class it fails at execution with "No main class
        // specified and classpath is not an executable jar"
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set("com.jpage4500.devicemanager.MainKt")
        }
    }

    @Suppress("unused")
    sourceSets {
        val commonMain by getting {
            dependencies {
                // named explicitly rather than through the plugin's `compose.*` accessors, which
                // are deprecated in 1.11 — and material3 is not on the same version as the rest
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.backhandler)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kermit)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
                // the adb client's socket layer — see docs/ADB-TRANSPORTS.md
                implementation(libs.ktor.network)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }

        // desktop and android are both JVM, so everything that wants java.* lives here rather than
        // being written twice. iOS is the only target that doesn't see it
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // desktop and android share one http client (manager/HttpClientFactory.jvm.kt), so
                // the engine is declared once here rather than in both leaves
                implementation(libs.ktor.client.okhttp)
            }
        }

        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.play.services.code.scanner)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.compose.runtime)
                // every platform's skiko natives, so the one jar runs everywhere — a dev build
                // pulls the lot too, which costs disk and saves a per-OS build variant
                implementation(libs.desktop.jvm.linux.arm64)
                implementation(libs.desktop.jvm.linux.x64)
                implementation(libs.desktop.jvm.macos.arm64)
                implementation(libs.desktop.jvm.macos.x64)
                implementation(libs.desktop.jvm.windows.x64)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.slf4j.simple)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}

android {
    namespace = "com.jpage4500.devicemanager"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.jpage4500.devicemanager.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = brandAppName
            packageVersion = appVersion

            // written by scripts/generate-icons.sh — guarded so a checkout that hasn't run it
            // still packages, just with jpackage's default icon
            val macOSIcon = project.file("src/desktopMain/resources/icon.icns")
            val windowsIcon = project.file("src/desktopMain/resources/icon.ico")
            val linuxIcon = project.file("src/desktopMain/resources/icon.png")

            macOS {
                bundleID = brandBundleId
                if (macOSIcon.exists()) {
                    iconFile.set(macOSIcon)
                }
                // the traffic lights and the AWT file dialogs are drawn by AppKit; this is what
                // makes them follow the system appearance
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSHighResolutionCapable</key>
                        <true/>
                    """.trimIndent()
                }
            }

            if (windowsIcon.exists()) {
                windows {
                    iconFile.set(windowsIcon)
                }
            }

            if (linuxIcon.exists()) {
                linux {
                    iconFile.set(linuxIcon)
                }
            }
        }
    }
}

tasks.register("printVersion") {
    val versionToPrint = appVersion
    doLast { println(versionToPrint) }
}

// one fat jar with every platform's natives, for jDeploy
val desktopJarTask = tasks.named<Jar>("desktopJar")

tasks.register<Jar>("buildExecutableJar") {
    group = "build"
    description = "Build cross-platform executable JAR for jDeploy"
    dependsOn(desktopJarTask)

    archiveBaseName.set("adm")
    archiveClassifier.set("all")

    manifest {
        attributes["Main-Class"] = "com.jpage4500.devicemanager.MainKt"
    }

    val runtimeClasspath = configurations.getByName("desktopRuntimeClasspath")
    from({ runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) } })
    from(zipTree(desktopJarTask.get().archiveFile))

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
