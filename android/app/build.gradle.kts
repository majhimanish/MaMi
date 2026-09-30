import groovy.json.JsonSlurper
import java.io.File
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---- Configuration -------------------------------------------------------------
// Set these in ~/.gradle/gradle.properties or with -P on the command line.

/** Server the app talks to. Debug builds default to the host machine of an emulator. */
val serverUrl: String = providers.gradleProperty("mami.serverUrl").orNull ?: ""
/** Android ABIs to build the Rust core for, comma separated. */
val rustAbis: List<String> =
    (providers.gradleProperty("mami.abis").orNull ?: "arm64-v8a,armeabi-v7a,x86_64").split(",").map { it.trim() }

/** Firebase settings, read from app/google-services.json when present (push notifications). */
val firebase: Map<String, String> = readFirebaseConfig(file("google-services.json"), "app.mami")

android {
    namespace = "app.mami"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.mami"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Only ship ABIs the Rust core is built for (JNA brings more).
        ndk { abiFilters += rustAbis }

        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebase["projectId"].orEmpty()}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebase["appId"].orEmpty()}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebase["apiKey"].orEmpty()}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebase["senderId"].orEmpty()}\"")
    }

    signingConfigs {
        // Release signing comes from the environment (a CI secret), never from the repo.
        val keystore = System.getenv("MAMI_KEYSTORE_FILE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("MAMI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MAMI_KEY_ALIAS")
                keyPassword = System.getenv("MAMI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"${serverUrl.ifEmpty { "http://10.0.2.2:8080" }}\"")
        }
        release {
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            // Keep native libraries uncompressed and page-aligned (Android 15+ 16 KB pages).
            useLegacyPackaging = false
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

// ---- The Rust core ---------------------------------------------------------------
// Builds core/ for every ABI with cargo-ndk and generates its Kotlin bindings.
// Needs Rust (rustup), `cargo install cargo-ndk` and the Android NDK.

abstract class BuildRustCore : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val kotlinDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val root = workspace.get().asFile
        val cargo = findCargo()
        val jniLibs = jniLibsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val kotlin = kotlinDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val ndk = ndkDirectory.get().asFile.absolutePath

        exec.exec {
            workingDir = root
            environment("ANDROID_NDK_HOME", ndk)
            commandLine(
                listOf(cargo, "ndk") +
                    abis.get().flatMap { listOf("-t", it) } +
                    listOf("--platform", minSdk.get().toString(), "-o", jniLibs.absolutePath) +
                    listOf("build", "-p", "mami-core", "--release"),
            )
        }
        // Bindings are generated from an unstripped host build of the same crate.
        exec.exec {
            workingDir = root
            commandLine(cargo, "build", "-p", "mami-core")
        }
        val os = System.getProperty("os.name").lowercase()
        val hostLibrary = when {
            os.contains("mac") -> "libmami_core.dylib"
            os.contains("windows") -> "mami_core.dll"
            else -> "libmami_core.so"
        }
        exec.exec {
            workingDir = root
            commandLine(
                cargo, "run", "-q", "-p", "uniffi-bindgen", "--",
                "generate", "--library", File(root, "target/debug/$hostLibrary").absolutePath,
                "--language", "kotlin", "--no-format", "--out-dir", kotlin.absolutePath,
            )
        }
    }

    private fun findCargo(): String {
        System.getenv("CARGO")?.let { return it }
        val home = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        return if (home.exists()) home.absolutePath else "cargo"
    }
}

val repoRoot: File = rootDir.parentFile

androidComponents {
    onVariants { variant ->
        val task = tasks.register<BuildRustCore>("buildRustCore${variant.name.replaceFirstChar { it.uppercase() }}") {
            sources.from(
                fileTree(repoRoot.resolve("core/src")),
                repoRoot.resolve("core/Cargo.toml"),
                repoRoot.resolve("core/uniffi.toml"),
                repoRoot.resolve("Cargo.lock"),
            )
            abis.set(rustAbis)
            minSdk.set(libs.versions.minSdk.get().toInt())
            workspace.set(repoRoot)
            ndkDirectory.set(
                providers.environmentVariable("ANDROID_NDK_HOME")
                    .map { layout.projectDirectory.dir(it) }
                    .orElse(sdkComponents.ndkDirectory),
            )
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, BuildRustCore::jniLibsDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(task, BuildRustCore::kotlinDir)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // UniFFI bindings call into the Rust core through JNA.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
}

fun readFirebaseConfig(file: File, packageName: String): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val json = JsonSlurper().parse(file) as Map<*, *>
    val project = json["project_info"] as Map<*, *>
    val client = (json["client"] as List<*>).map { it as Map<*, *> }.firstOrNull {
        val info = it["client_info"] as Map<*, *>
        (info["android_client_info"] as Map<*, *>)["package_name"] == packageName
    } ?: error("google-services.json has no Android app with package $packageName")
    val apiKey = (client["api_key"] as List<*>).map { it as Map<*, *> }.first()["current_key"] as String
    return mapOf(
        "projectId" to project["project_id"] as String,
        "senderId" to project["project_number"] as String,
        "appId" to (client["client_info"] as Map<*, *>)["mobilesdk_app_id"] as String,
        "apiKey" to apiKey,
    )
}
