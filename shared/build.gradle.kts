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
