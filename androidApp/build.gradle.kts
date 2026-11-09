import java.io.ByteArrayOutputStream
import java.util.*

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

// release signing: an env variable, then ~/.gradle/gradle.properties (which Gradle reads as project
// properties). the KMP_ keystore is the one shared across the KMP apps; every release has to be
// signed with it or an installed copy can't update in place. see docs/RELEASING.md
fun signingValue(key: String): String =
    (System.getenv(key) ?: project.findProperty(key) as String?)?.trim().orEmpty()

// the keystore arrives base64-encoded; decode it under build/ so a plain ./gradlew works without
// the wrapper script. missing, the release build is simply left unsigned
val releaseKeystore: File? = signingValue("KMP_KEYSTORE").takeIf { it.isNotBlank() }?.let { encoded ->
    File(layout.buildDirectory.get().asFile, "tmp/adm-release.jks").apply {
        parentFile.mkdirs()
        writeBytes(Base64.getDecoder().decode(encoded))
    }
}

android {
    namespace = "com.jpage4500.devicemanager.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jpage4500.devicemanager"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = commitCount
        versionName = "2.0.$commitCount"
        // the composeApp library ships no strings.xml, so this is the only source of the label
        resValue("string", "app_name", brandAppName)

        // arm64 only, which is every phone this app is for. The weight here is Conscrypt's
        // libconscrypt_jni.so at ~2.6 MB per ABI, and shipping four of them put ~10 MB in a
        // universal APK to serve one. See adb/pairing/PairingTls.android.kt for why it is bundled.
        //
        // The cost is emulators: an x86_64 image has no Conscrypt native library and so cannot
        // pair. That degrades rather than breaks — `canPairDirectly` asks Conscrypt whether it
        // actually loaded — and an Apple-silicon emulator is arm64 anyway.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("KMP_SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("KMP_SIGNING_KEY_ALIAS")
                keyPassword = signingValue("KMP_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
