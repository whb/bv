plugins {
    alias(gradleLibs.plugins.google.ksp)
    alias(gradleLibs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(AppConfiguration.jdk))
    }
}

dependencies {
    ksp(libs.material.symbols.compose.ksp)
    implementation(libs.material.symbols.compose.annotation)
    compileOnly(androidx.compose.ui)
}

// material-symbols 的 KSP 处理器会从 raw.githubusercontent.com 拉取图标 XML，
// 国内网络易超时导致 :symbols:kspKotlin FAILED (SocketTimeoutException)。
// 指定一个持久化缓存目录后，首次成功下载即落地本地，后续重编复用，不再联网。
ksp {
    arg("materialSymbols.network.cache.cacheDirectory", "$rootDir/.material-symbols-cache")
}