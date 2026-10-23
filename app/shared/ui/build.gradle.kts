import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The Terms of Use and Privacy Policy, from the repository's legal/ folder (the one source; the Website's
// pages are built from the same files by legal/build_pages.py).
val generateLegalText = tasks.register("generateLegalText") {
    val legal = rootProject.layout.projectDirectory.dir("../legal")
    val terms = legal.file("terms.md")
    val privacy = legal.file("privacy.md")
    val out = layout.buildDirectory.dir("generated/legal/kotlin")
    inputs.files(terms, privacy)
    outputs.dir(out)
    doLast {
        fun raw(f: RegularFile) = f.asFile.readText().replace("\$", "\${'$'}").also {
            check("\"\"\"" !in it) { "${f.asFile.name}: no triple quotes" }
        }
        val file = out.get().file("llc/applabs/switchboard/ui/settings/LegalText.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package llc.applabs.switchboard.ui.settings\n\n// Generated from legal/*.md by :shared:ui:generateLegalText. Don't edit.\n" +
            "internal object LegalText {\n    const val TERMS = \"\"\"" + raw(terms) + "\"\"\"\n    const val PRIVACY = \"\"\"" + raw(privacy) + "\"\"\"\n}\n")
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Ui"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "llc.applabs.switchboard.ui"
        // Run commonTest on the JVM too — iOS tests cannot run on Linux, and
        // without this no test runs outside macOS (CI).
        withHostTest {}
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain { kotlin.srcDir(generateLegalText.map { it.outputs.files.singleFile }) }
        commonMain.dependencies {
            // Public API exposes domain types (AppDependencies), so consumers need them too
            api(projects.shared.domain)
            api(projects.shared.design)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.qrcodegen)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
        // Only the iOS entry point (MainViewController) builds the AppContainer;
        // common UI code depends on domain interfaces (AppDependencies) only.
        iosMain.dependencies {
            implementation(projects.shared.data)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
