import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * AdMob's app ID and the two ad units (the Stats/History banner and the Trips native card). Never
 * in the code: debug builds use Google's published test IDs ([AdMobIds.TEST]), and release builds
 * read the real ones from `admob.properties` at the repository root, which Git ignores. Without
 * that file a release build has no ads at all, so it can never ship the test IDs. See
 * docs/RELEASING.md, "Ads (AdMob IDs)".
 */
data class AdMobIds(val appId: String, val bannerUnitId: String, val nativeUnitId: String) {
    companion object {
        /** https://developers.google.com/admob/android/test-ads: the sample app ID, adaptive banner and native ad units. */
        val TEST = AdMobIds(
            appId = "ca-app-pub-3940256099942544~3347511713",
            bannerUnitId = "ca-app-pub-3940256099942544/9214589741",
            nativeUnitId = "ca-app-pub-3940256099942544/2247696110",
        )
        private const val TEST_PUBLISHER = "3940256099942544"
        // The forms the Mobile Ads SDK accepts: it stops the app at launch with an app ID in any other form.
        private val APP_ID = Regex("ca-app-pub-[0-9]{16}~[0-9]{10}")
        private val UNIT_ID = Regex("ca-app-pub-[0-9]{16}/[0-9]{10}")

        /** The release IDs from [file], or null when there is no such file. A file that is there but wrong fails the build. */
        fun release(file: File): AdMobIds? {
            if (!file.isFile) return null
            val properties = Properties().apply { file.inputStream().use(::load) }
            fun id(key: String, format: Regex): String {
                val value = properties.getProperty(key)?.trim().orEmpty()
                if (!format.matches(value)) throw GradleException("${file.name}: $key must look like ${format.pattern}, not \"$value\"")
                if (TEST_PUBLISHER in value) throw GradleException("${file.name}: $key is one of Google's test IDs; a release must never use them")
                return value
            }
            return AdMobIds(id("appId", APP_ID), id("bannerUnitId", UNIT_ID), id("nativeUnitId", UNIT_ID))
        }
    }
}

val releaseAdMobIds: AdMobIds? = AdMobIds.release(rootProject.file("admob.properties"))

android {
    // The Kotlin package and R class stay com.example.stepsplit; only applicationId is the app's
    // identity on devices and Google Play.
    namespace = "com.example.stepsplit"
    compileSdk = 36

    defaultConfig {
        // Permanent from the first Play upload: it can never change after that. See docs/RELEASING.md.
        applicationId = "bukin.stepsgpsroute"
        minSdk = 26
        targetSdk = 36
        // versionCode goes up by 1 for every upload to Play, on any track; see docs/RELEASING.md.
        versionCode = 1
        versionName = "1.0"
        // Offline map packs: the list of maps (maps.json) next to their files, in the repo's "maps"
        // GitHub release. See scripts/offline-maps/README.md.
        buildConfigField("String", "MAPS_MANIFEST_URL", "\"https://github.com/EliBukin/steps/releases/download/maps/maps.json\"")
        // Debug builds only (see the debug build type): a region to test Google's consent form in.
        buildConfigField("String", "UMP_DEBUG_GEOGRAPHY", "\"\"")
        buildConfigField("String", "UMP_TEST_DEVICE_HASHED_ID", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            adMobIds(releaseAdMobIds)
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            adMobIds(AdMobIds.TEST)
            // To see the consent form outside Europe: ./gradlew installDebug -Pstepsplit.umpDebugGeography=EEA
            // -Pstepsplit.umpTestDeviceHashedId=<the ID the UMP SDK logs on the phone>. Emulators need no ID.
            buildConfigField("String", "UMP_DEBUG_GEOGRAPHY", "\"${providers.gradleProperty("stepsplit.umpDebugGeography").getOrElse("")}\"")
            buildConfigField("String", "UMP_TEST_DEVICE_HASHED_ID", "\"${providers.gradleProperty("stepsplit.umpTestDeviceHashedId").getOrElse("")}\"")
        }
    }

    // A release without admob.properties has no ads: no app ID, and without one the Mobile Ads
    // SDK's start-up provider would stop the app at launch, so that build leaves it out.
    if (releaseAdMobIds == null) {
        sourceSets.getByName("release").manifest.srcFile("src/adsOff/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Deliberately no explicit jvmToolchain(): AGP 8.13 only requires JDK 17+ to build, and
    // pinning a toolchain version here would force Gradle to auto-provision that exact JDK
    // (requiring network access / the foojay plugin) instead of just using whichever JDK
    // launched Gradle. The Kotlin compile target is still pinned to 17 explicitly (below) so
    // it stays consistent with compileOptions regardless of which JDK does the compiling.
    // Robolectric's API 36 shadow specifically needs JDK 21+ to *run* testDebugUnitTest (see
    // README) - but assembleDebug/lintDebug only need JDK 17+.
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    bundle {
        // Every language stays in the base APK, as in the release APK that gets tested: with
        // language splits, Play installs only the phone's languages, so English picked later as the
        // app's language in Android's settings could be missing. It adds about 0.4 MB to the
        // installed app and 70 KB to the download.
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/** [ids] null: no ads in this build type. */
fun com.android.build.api.dsl.ApplicationBuildType.adMobIds(ids: AdMobIds?) {
    manifestPlaceholders["admobAppId"] = ids?.appId.orEmpty()
    buildConfigField("boolean", "ADS_ENABLED", (ids != null).toString())
    buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"${ids?.bannerUnitId.orEmpty()}\"")
    buildConfigField("String", "ADMOB_NATIVE_UNIT_ID", "\"${ids?.nativeUnitId.orEmpty()}\"")
}

/**
 * Copies the privacy policy's source, `docs/privacy-policy.html` (the page published on GitHub
 * Pages), into the app's assets, where `PermissionsRationaleActivity` shows it. Health Connect
 * requires that screen to show the same policy as the Play listing, so the app never keeps a copy
 * of its own: every build takes the current source.
 */
abstract class CopyPrivacyPolicyTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val policy: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        policy.get().asFile.copyTo(directory.resolve("privacy-policy.html"))
    }
}

androidComponents {
    onVariants { variant ->
        val copyPolicy = tasks.register<CopyPrivacyPolicyTask>("copy${variant.name.replaceFirstChar(Char::uppercase)}PrivacyPolicy") {
            policy.set(rootProject.layout.projectDirectory.file("docs/privacy-policy.html"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyPolicy, CopyPrivacyPolicyTask::outputDirectory)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    // Not used directly - play-services-base transitively pulls in a very old fragment:1.1.0,
    // which trips the ActivityResult API's minimum-Fragment-version lint check. Force a modern one.
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.play.services.location)
    implementation(libs.androidx.health.connect.client)

    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    // Completed-trip detail map only (ui/trips/TripRouteMap.kt) - official MapLibre Compose,
    // consumed directly as a plain Android AAR (no Kotlin Multiplatform plugin needed; its
    // Android-target Compose dependencies resolve to the same real androidx Compose artifacts
    // already pinned by this project's own compose-bom). See README's "Trip detail map" section.
    implementation(libs.maplibre.compose.android) {
        // Its default MapLibre Android SDK draws with Vulkan only and makes the merged manifest
        // require android.hardware.vulkan.version, so Google Play would hide the whole app from
        // phones without Vulkan. Use MapLibre's OpenGL ES build instead: same API, same version
        // (maplibre-compose's documented "Revert to OpenGL on Android" setup).
        exclude(group = "org.maplibre.gl", module = "android-sdk")
    }
    implementation(libs.maplibre.android.opengl)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
}
