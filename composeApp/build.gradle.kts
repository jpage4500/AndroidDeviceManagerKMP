import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date

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
    useKotlinOutput {
        topLevelConstants = true
        internalVisibility = false
    }
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
        }

        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
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
                implementation(libs.ktor.client.okhttp)
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

            macOS {
                bundleID = brandBundleId
                // the traffic lights and the AWT file dialogs are drawn by AppKit; this is what
                // makes them follow the system appearance
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSHighResolutionCapable</key>
                        <true/>
                    """.trimIndent()
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
