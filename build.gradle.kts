import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "io.acr"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    // Coroutines + serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    // ULID primary keys
    implementation("com.github.f4b6a3:ulid-creator:5.2.3")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Los tests NO deben tocar los datos reales del usuario. Con ACR_LIVE=1 se apunta a la
    // instalación real, para los tests que verifican repos y tokens de verdad.
    if (System.getenv("ACR_LIVE") != "1") {
        systemProperty("acr.dataDir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    }
}

compose.desktop {
    application {
        mainClass = "io.acr.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AI Code Reviewer"
            packageVersion = "1.0.0"
            description = "Review pull requests with Claude Code"
            vendor = "Viktor Karpyuk"

            // Trimmed jlink runtime. java.net.http is required by the forge clients;
            // java.sql by sqlite-jdbc.
            modules("java.sql", "java.naming", "java.net.http", "jdk.crypto.ec", "jdk.unsupported")

            macOS {
                bundleID = "io.acr.reviewer"
                iconFile.set(project.file("icon/acr-icon.icns"))
            }
            windows {
                iconFile.set(project.file("icon/acr-icon.ico"))
            }
            linux {
                iconFile.set(project.file("icon/acr-icon.png"))
            }
        }
    }
}
