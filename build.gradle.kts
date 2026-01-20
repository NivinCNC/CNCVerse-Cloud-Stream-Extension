import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.0")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Load secrets from local.properties if available
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

// Helper to read secret from local.properties or system environment or fallback
fun getSecret(key: String, fallback: String = ""): String {
    return localProperties.getProperty(key)
        ?: System.getenv(key)
        ?: fallback
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension")
        authors = listOf("NivinCNC")
    }

    android {
        namespace = "com.cncverse"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35

            // Inject secrets into BuildConfig
            buildConfigField("String", "MOVIEBOX_SECRET_KEY_DEFAULT", "\"${getSecret("MOVIEBOX_SECRET_KEY_DEFAULT")}\"")
            buildConfigField("String", "MOVIEBOX_SECRET_KEY_ALT", "\"${getSecret("MOVIEBOX_SECRET_KEY_ALT")}\"")
            buildConfigField("String", "CASTLE_SUFFIX", "\"${getSecret("CASTLE_SUFFIX")}\"")
            buildConfigField("String", "SIMKL_API", "\"${getSecret("SIMKL_API")}\"")
            buildConfigField("String", "MAL_API", "\"${getSecret("MAL_API")}\"")
            buildConfigField("String", "LIBRARY_PACKAGE_NAME", "\"com.cncverse\"")
            buildConfigField("String", "CRICIFY_PROVIDER_SECRET1", "\"${getSecret("CRICIFY_PROVIDER_SECRET1")}\"")
            buildConfigField("String", "CRICIFY_PROVIDER_SECRET2", "\"${getSecret("CRICIFY_PROVIDER_SECRET2")}\"")
            buildConfigField("String", "PIKASHOW_API_KEY", "\"${getSecret("PIKASHOW_API_KEY")}\"")
            buildConfigField("String", "PIKASHOW_HMAC_SECRET", "\"${getSecret("PIKASHOW_HMAC_SECRET")}\"")
            buildConfigField("String", "CRICFY_FIREBASE_API_KEY", "\"${getSecret("CRICFY_FIREBASE_API_KEY")}\"")
            buildConfigField("String", "CRICFY_FIREBASE_APP_ID", "\"${getSecret("CRICFY_FIREBASE_APP_ID")}\"")
            buildConfigField("String", "CRICFY_FIREBASE_PROJECT_NUMBER", "\"${getSecret("CRICFY_FIREBASE_PROJECT_NUMBER")}\"")
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        implementation("org.mozilla:rhino:1.8.0")
        implementation("com.google.code.gson:gson:2.11.0")
        implementation("androidx.annotation:annotation:1.7.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
