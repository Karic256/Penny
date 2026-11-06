plugins {
    id("com.android.application")
}

android {
    namespace = "com.karic.penny"
    compileSdk = 35
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)

    defaultConfig {
        applicationId = "com.karic.penny"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
