plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.auralis.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.auralis.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 15
        versionName = "11.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // play-services-nearby 19.3.0 要求开启（19.5.0 起才取消了这个要求，但 19.5.x 的 Kotlin 元数据版本太新，见下）
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.6")

    // 👇 听哥哥的！直接上 Media3 1.10.0 满血最新版！
    val media3Version = "1.10.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
    implementation("androidx.car.app:app-automotive:1.7.0-beta02")
    implementation("androidx.car.app:app:1.7.0-beta02")

    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // Google Drive 云备份：仅用登录拿 OAuth token，实际 Drive API 走现成的 OkHttp 直接调 REST 接口，
    // 不引入体积更大的 google-api-client / google-api-services-drive。
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // Nearby Connections：不依赖路由器的手机对手机传输（蓝牙发现 + Wi-Fi 直连/热点自动升级）。
    // 不能用 19.5.x：它的 .kotlin_module 是用 Kotlin 2.4 编译的（元数据版本 2.4.0），
    // 而本项目是 Kotlin 2.0.21（只认到 2.1.0），KSP 阶段会报 "Module was compiled with an incompatible version of Kotlin"。
    // 19.3.0 是 2024 年 5 月发布的、早于这批新版本的最后一个版本，元数据版本较旧；
    // 这个版本需要开启 core library desugaring（见上面 compileOptions 和下面的 coreLibraryDesugaring 依赖）。
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.palette:palette-ktx:1.0.0")


    val roomVersion = "2.6.1" // Room 目前非常稳定，这个版本就足够强悍啦
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 桌面小组件：用 Glance 而不是传统 RemoteViews 手搓 XML 布局，
    // 复用 Compose 的写法（虽然渲染机制不同），维护成本低很多
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}