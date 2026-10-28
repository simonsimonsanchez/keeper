plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.keeper.car"
    compileSdk = 35
    useLibrary("android.car", false)  // AAOS 표준 Car API (Pleos Connect = Android Automotive 기반)
    defaultConfig {
        applicationId = "app.keeper.car"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("demo") {
            storeFile = rootProject.file("keystore/keeper-demo.jks")
            storePassword = "keeperdemo"
            keyAlias = "keeper"
            keyPassword = "keeperdemo"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demo")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(project(":core"))

    // Pleos Connect SDK (Pleos Playground 공식 저장소)
    implementation("ai.pleos.playground:Vehicle:2.3.2")        // 기어·브레이크·착석·도어·조명
    implementation("ai.pleos.playground:FusedLocation:1.1.3")  // 위치 추적·발레 반경(Proximity Alert)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // 화면 캡처 테스트 (Robolectric 네이티브 그래픽)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
