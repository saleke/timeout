import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

abstract class GenerateDesignTokens : DefaultTask() {
    @get:InputFile abstract val tokenFile: RegularFileProperty
    @get:OutputDirectory abstract val kotlinOutput: DirectoryProperty
    @get:OutputDirectory abstract val resourceOutput: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val tokens = JsonSlurper().parse(tokenFile.get().asFile) as Map<String, Any>
        val refs = tokens["reference"] as Map<*, *>
        val themes = tokens["theme"] as Map<*, *>
        val type = tokens["typography"] as Map<*, *>
        val motion = tokens["motion"] as Map<*, *>
        val output = kotlinOutput.file("com/timeout/app/ui/theme/DesignTokens.kt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(buildString {
            appendLine("// Generated from design-system/timeout/tokens.json. Do not edit.")
            appendLine("package com.timeout.app.ui.theme")
            appendLine("import androidx.compose.ui.graphics.Color")
            appendLine("internal object DesignTokens {")
            themes.forEach { (name, roles) ->
                appendLine("val $name = mapOf(")
                (roles as Map<*, *>).forEach { (role, alias) ->
                    val ref = alias.toString().removePrefix("{reference.").removeSuffix("}")
                    val hex = refs[ref].toString().removePrefix("#")
                    appendLine("\"$role\" to Color(0xFF${hex}L),")
                }
                appendLine(")")
            }
            type.filterValues { it is Map<*, *> }.forEach { (name, value) ->
                val spec = value as Map<*, *>
                appendLine("const val ${name}Size = ${spec["sizeSp"]}")
                appendLine("const val ${name}Line = ${spec["lineHeightSp"]}")
            }
            listOf("pressMs", "feedbackMs", "contentChangeMs", "sheetEnterMs", "sheetExitMs", "screenChangeMs",
                "screenPopMs", "tabExitMs", "tabEnterMs", "tabEnterDelayMs").forEach {
                appendLine("const val $it = ${motion[it]}")
            }
            appendLine("const val pressScale = ${motion["pressScale"]}f")
            (motion["customEaseOut"] as List<*>).forEachIndexed { index, value -> appendLine("const val easeOut$index = ${value}f") }
            val choice = (tokens["components"] as Map<*, *>)["choiceGroup"] as Map<*, *>
            choice.forEach { (name, value) -> appendLine("const val choice${name.toString().replaceFirstChar { it.uppercase() }} = $value") }
            appendLine("}")
        })
        themes.forEach { (name, roles) ->
            val directory = if (name == "dark") "values-night" else "values"
            val resources = resourceOutput.file("$directory/timeout_colors.xml").get().asFile
            resources.parentFile.mkdirs()
            resources.writeText(buildString {
                appendLine("<resources>")
                listOf("background", "primary").forEach { role ->
                    val alias = (roles as Map<*, *>)[role].toString().removePrefix("{reference.").removeSuffix("}")
                    appendLine("<color name=\"timeout_$role\">${refs[alias]}</color>")
                }
                appendLine("</resources>")
            })
        }
        val brand = tokens["brand"] as Map<*, *>
        val brandResources = resourceOutput.file("values/logo_colors.xml").get().asFile
        brandResources.parentFile.mkdirs()
        brandResources.writeText(buildString {
            appendLine("<!-- Generated from the shared brand token graph. -->")
            appendLine("<resources>")
            mapOf("background" to "iconBackground").forEach { (name, role) ->
                val ref = brand[role].toString().removePrefix("{reference.").removeSuffix("}")
                appendLine("<color name=\"logo_$name\">${refs[ref]}</color>")
            }
            appendLine("</resources>")
        })
    }
}

val generateDesignTokens = tasks.register<GenerateDesignTokens>("generateDesignTokens") {
    tokenFile.set(rootProject.layout.projectDirectory.file("design-system/timeout/tokens.json"))
    kotlinOutput.set(layout.buildDirectory.dir("generated/source/designTokens"))
    resourceOutput.set(layout.buildDirectory.dir("generated/res/designTokens"))
}

android {
    namespace = "com.timeout.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.timeout.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
        buildConfigField("boolean", "ALLOW_TEST_DEVICE_REMOVAL", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        debug { buildConfigField("boolean", "ALLOW_TEST_DEVICE_REMOVAL", "true") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("performance") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            buildConfigField("boolean", "ALLOW_TEST_DEVICE_REMOVAL", "true")
            matchingFallbacks += listOf("release")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateDesignTokens, GenerateDesignTokens::kotlinOutput)
    variant.sources.res?.addGeneratedSourceDirectory(generateDesignTokens, GenerateDesignTokens::resourceOutput)
    variant.outputs.forEach { output ->
        output.outputFileName.set(output.versionName.map { version ->
            if (variant.buildType == "debug") "timeout-$version.apk" else "timeout-$version-${variant.buildType}.apk"
        })
    }
}

ksp { arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path) }

dependencies {
    implementation(project(":core"))
    // Compose 1.11.4 is the newest line verified with this project's Kotlin 2.2 toolchain.
    //noinspection GradleDependency
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    // Serialization 1.13 requires Kotlin 2.4; AGP 9.5 built-in Kotlin is 2.2.
    //noinspection NewerVersionAvailable
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    //noinspection GradleDependency
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}
