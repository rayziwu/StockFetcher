// C:\Users\rayzi\AndroidStudioProjects\StockFether\app\build.gradle.kts 完整內容

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.stockfetcher"

    // 【重要】: 這裡假設您的 libs.versions.toml 已經定義了 release(36)，否則請直接使用數字 34 或 35
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.stockfether"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "3.5.1"

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
    }

    // 【已移除】: 由於 settings.gradle.kts 的嚴格模式，此處不能再宣告 repositories 區塊
    /*
    repositories {
        maven("https://jitpack.io")
    }
    */
}

dependencies {
    // Android 核心依賴
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // 【新增/確認】: 股票數據和圖表核心依賴
    // 1. Yahoo Finance API (需要 JitPack 倉庫)
    //implementation("com.savoirfairey:yahoofinance-api:3.16.0")
    // 2. HTTP 客戶端 (OkHttp, Yahoo API 可能需要)
    implementation(libs.okhttp)
    // 3. 圖表函式庫
    implementation(libs.mpandroidchart)
    // 4. Gson (如果您專案有用到，保留)
    implementation("com.google.code.gson:gson:2.13.2")
    implementation(libs.jsoup)

    // 測試依賴
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

}