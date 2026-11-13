import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Clé de signature release : hors du dépôt.
// Chemin par défaut ~/.trackly/keystore.properties, ou variable TRACKLY_KEYSTORE_PROPERTIES.
val keystorePropertiesFile = File(
    System.getenv("TRACKLY_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.trackly/keystore.properties",
)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "app.trackly"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.trackly"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.3.1"
        // Appel : la librairie WebRTC embarque un .so par architecture (12 à 16 Mo chacun). Les téléphones
        // visés et l'émulateur de test sont en arm64 : on ne garde que celle-là.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
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

    buildFeatures {
        compose = true
    }

    // Les bibliothèques embarquent leurs textes dans 85 langues : l'app n'est qu'en français (environ 200 Ko).
    androidResources {
        localeFilters += "fr"
    }

    // Les tests de migration (Robolectric) lisent les schémas exportés dans les assets de la
    // variante testée : Robolectric ignore les assets de `src/test`. Seul l'APK debug les
    // embarque (quelques Ko, aucune donnée) ; l'APK release n'en contient pas.
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")
    // Historique facultatif embarqué dans l'APK release : dossier hors du dépôt, contenant
    // preload/trackly-export.json. Variable absente : l'APK ne contient aucune donnée.
    System.getenv("TRACKLY_PRELOAD_DIR")?.let { dir ->
        require(File(dir, "preload/trackly-export.json").isFile) { "Export introuvable dans TRACKLY_PRELOAD_DIR" }
        sourceSets.getByName("release").assets.directories.add(dir)
    }
    // Jeu d'essai de parité synthétique : tests seulement, jamais dans l'APK.
    sourceSets.getByName("test").resources.directories.add("$rootDir/fixtures/parity")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // L'app suit le fuseau du téléphone ; les tests fixent un fuseau avec heure d'été.
                it.systemProperty("user.timezone", "Europe/Zurich")
            }
        }
    }
}

// Schémas exportés et commités : base des migrations et de leurs tests.
room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Refuse de produire un APK release non signé : sans la clé, pas de mise à jour possible.
val verifierCleRelease by tasks.registering {
    val present = hasReleaseKey
    val chemin = keystorePropertiesFile.path
    doLast {
        if (!present) {
            throw GradleException(
                "Clé de signature introuvable ($chemin) : fichier attendu avec storeFile, storePassword, keyAlias et keyPassword.",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifierCleRelease) }

// Contrôles réels contre OpenAI (`app.trackly.live`) : seulement avec
// -PliveChecks=true. La clé arrive par la variable TRACKLY_LIVE_OPENAI_KEY, héritée par la JVM de
// test ; elle n'est jamais lue ici. Sans ce drapeau, les contrôles sont ignorés (aucun appel réseau).
if (providers.gradleProperty("liveChecks").orNull == "true") {
    // Le cache de configuration peut conserver l'état des tâches : on l'interdit ici.
    if (gradle.startParameter.isConfigurationCacheRequested) {
        throw GradleException("Contrôles réels : relance avec --no-configuration-cache.")
    }
    tasks.withType<Test>().configureEach {
        systemProperty("trackly.liveChecks", "true")
        // Toujours rejoués, jamais mis en cache (les rapports ne quittent pas app/build).
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Assistant : client HTTP maison de la Responses API (pas de SDK), JSON via kotlinx.serialization.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    // Sauvegarde : tâche de nuit, accès Drive (portée drive.file), appels REST.
    implementation(libs.work.runtime)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    // Clé OpenAI par QR code : appareil photo (CameraX) et décodage (ZXing core), tout sur le téléphone.
    implementation(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // Appel : WebRTC vers GPT-Live (voix), sans SDK OpenAI.
    implementation(libs.webrtc)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
}
