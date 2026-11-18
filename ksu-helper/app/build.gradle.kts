plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.xxl.ksuhelper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xxl.ksuhelper"
        minSdk = 26
        targetSdk = 37
        versionCode = 64
        versionName = "7.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // 授权流程测试用变体：包名带 .p2，安装后权限是未授予状态，
        // 可用于验证 Shizuku 的授权弹窗与回调。
        create("probe") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".p2"
            versionNameSuffix = "-p2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress.add("ko")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation("junit:junit:4.13.2")
}
