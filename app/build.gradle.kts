@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(gradleLibs.plugins.android.application)
    alias(gradleLibs.plugins.compose.compiler)
    alias(gradleLibs.plugins.firebase.crashlytics)
    alias(gradleLibs.plugins.google.ksp)
    alias(gradleLibs.plugins.google.services) apply false
    alias(gradleLibs.plugins.kotlin.android)
    alias(gradleLibs.plugins.kotlin.serialization)
}

if (AppConfiguration.googleServicesAvailable) {
    apply(plugin = gradleLibs.plugins.google.services.get().pluginId)
}

// 架构参数开关（仅影响 release）：
//   不传参数            → 仅 armeabi-v7a
//   -PdualAbi=true      → arm64-v8a + armeabi-v7a
//   -Puniversal=true    → 全部 4 种架构（x86_64 / x86 / arm64-v8a / armeabi-v7a）
// 非 release（如 debug）无论是否传参，都打全部 4 种架构。
// universal 优先于 dualAbi（全部架构已包含双架构）。
val dualAbi = gradle.startParameter.projectProperties["dualAbi"] == "true"
val universal = gradle.startParameter.projectProperties["universal"] == "true"

// 全部架构（非 release 默认发布）
val allAbis = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")

// VLC 相关 native 库：仅 default flavor 包含，lite flavor 全部剔除（release、debug 均剔除）
val vlcLibNames = listOf("libvlc", "libc++_shared", "libvlcjni")
val vlcExcludes = vlcLibNames.flatMap { lib -> allAbis.map { abi -> "lib/$abi/$lib.so" } }

val signingProp = file(project.rootProject.file("signing.properties"))

android {
    signingConfigs {
        if (signingProp.exists()) {
            val properties = Properties().apply {
                load(FileInputStream(signingProp))
            }
            create("key") {
                storeFile = rootProject.file(properties.getProperty("keystore.path"))
                storePassword = properties.getProperty("keystore.pwd")
                keyAlias = properties.getProperty("keystore.alias")
                keyPassword = properties.getProperty("keystore.alias_pwd")
            }
        }
    }

    namespace = AppConfiguration.appId
    compileSdk = AppConfiguration.compileSdk

    defaultConfig {
        applicationId = AppConfiguration.appId
        minSdk = AppConfiguration.minSdk
        targetSdk = AppConfiguration.targetSdk
        versionCode = AppConfiguration.versionCode
        versionName = AppConfiguration.versionName
        vectorDrawables {
            useSupportLibrary = true
        }
        // ndk.abiFilters 按 buildType 在下方 buildTypes 中设定：
        // release 限制为 armeabi-v7a（-PdualAbi 加 arm64-v8a / -Puniversal 打全部 4 种）；debug 等不设 → 默认全部 4 种架构
    }

    flavorDimensions.add("channel")

    productFlavors {
        create("lite") {
            dimension = "channel"
        }
        create("default") {
            dimension = "channel"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingProp.exists()) signingConfig = signingConfigs.getByName("key")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = AppConfiguration.googleServicesAvailable
            }
            // 架构：release 默认仅 armeabi-v7a；-PdualAbi 加 arm64-v8a；-Puniversal 打全部 4 种
            ndk {
                abiFilters += when {
                    universal -> allAbis
                    dualAbi -> listOf("arm64-v8a", "armeabi-v7a")
                    else -> listOf("armeabi-v7a")
                }
            }
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".debug"
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        create("r8Test") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".r8test"
            if (signingProp.exists()) signingConfig = signingConfigs.getByName("key")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        create("alpha") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingProp.exists()) signingConfig = signingConfigs.getByName("key")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = AppConfiguration.googleServicesAvailable
            }
        }
    }

    buildFeatures {
        compose = true
        //buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "**/*.proto"
        }
        // VLC 相关 .so 的剔除改由 androidComponents.onVariants 按 flavor 处理（lite 剔除，default 保留）
    }

    applicationVariants.configureEach {
        val variant = this
        // 架构标签仅用于文件名，真实架构由 buildTypes.release.ndk.abiFilters 决定
        val abiLabel = when {
            variant.buildType.name != "release" -> "universal"      // 4 种全打
            universal -> "universal"                                // -Puniversal：4 种全打
            dualAbi -> "arm64_armeabi"                             // arm64-v8a + armeabi-v7a
            else -> "armeabi"                                       // 仅 armeabi-v7a
        }
        outputs.configureEach {
            (this as ApkVariantOutputImpl).apply {
                outputFileName =
                    "BV_${AppConfiguration.versionCode}_${AppConfiguration.versionName}.${variant.buildType.name}_${variant.flavorName}_$abiLabel.apk"
                versionNameOverride =
                    "${variant.versionName}.${variant.buildType.name}"
            }
        }
    }
}

// VLC 剔除：仅 lite flavor 剔除 libvlc / libc++_shared / libvlcjni（release、debug 均剔除）。
// 架构集合由 buildTypes.release.ndk.abiFilters（见上方 buildTypes）决定，不再按任务名嗅探。
androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.flavorName == "lite") {
            variant.packaging.jniLibs.excludes.addAll(vlcExcludes)
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_build_reports")
    stabilityConfigurationFiles.addAll(
        layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}

dependencies {
    implementation(project(":app:mobile"))
    implementation(project(":app:tv"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
