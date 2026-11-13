import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Moteur métier : Kotlin/JVM pur, aucune dépendance Android.
// Il détient toutes les règles (versions, date de rattachement, bilan, actions).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Jeu d'essai de parité (fixtures/parity), synthétique.
sourceSets.named("test") {
    resources.srcDir(rootProject.file("fixtures/parity"))
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // Lecture de l'export de reprise (arbre JSON seulement, sans plugin de sérialisation).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// L'app suit le fuseau du téléphone ; les tests fixent un fuseau avec heure d'été pour rester reproductibles.
tasks.withType<Test>().configureEach {
    systemProperty("user.timezone", "Europe/Zurich")
}
