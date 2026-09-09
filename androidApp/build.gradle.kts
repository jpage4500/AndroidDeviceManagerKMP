import java.io.ByteArrayOutputStream

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

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
}
