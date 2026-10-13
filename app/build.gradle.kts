plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.vlcglobaleq"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vlcglobaleq"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so CI produces an installable APK; use your own key for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Print every issue in the build log so CI failures are readable without the report artifact.
        textReport = true
        textOutput = file("stdout")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // android.jar only has org.json stubs for local unit tests.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
