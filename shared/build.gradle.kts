import java.io.File
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget()
    jvm()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.commons.compress)
        }

        jvmMain.dependencies {
            implementation(libs.commons.compress)
        }

        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.commons.compress)
        }
    }
}

android {
    namespace = "app.pocketdsl.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}

sqldelight {
    databases {
        create("PocketDslDatabase") {
            packageName.set("app.pocketdsl.db")
        }
    }
}

val jvmTest by tasks.existing(Test::class) {
    exclude("**/LocalDictionaryPackageImportSmokeTest.class")
}

tasks.register<Test>("smokeImportLocalDictionary") {
    description = "Runs the JVM-only local .tar.bz2 dictionary package import smoke test."
    group = "verification"

    testClassesDirs = jvmTest.get().testClassesDirs
    classpath = jvmTest.get().classpath
    include("**/LocalDictionaryPackageImportSmokeTest.class")

    val archivePath = providers.gradleProperty("dictionaryArchive")
        .map { path ->
            val file = File(path)
            if (file.isAbsolute) file.path else rootProject.file(path).path
        }
        .orElse(rootProject.file("dict-example/enruen-content-1.1.tar.bz2").path)
    systemProperty("pocketdsl.localDictionaryArchive", archivePath.get())

    testLogging {
        showStandardStreams = true
    }
}
