plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "in.bipul.gpsemulator"
    compileSdk = 37

    defaultConfig {
        applicationId = "in.bipul.gpsemulator"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("1.0.0-dev")
    }

    signingConfigs {
        create("release") {
            val keystore = providers.environmentVariable("GPS_EMULATOR_KEYSTORE").orNull
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("GPS_EMULATOR_KEYSTORE_PASSWORD").getOrElse("android")
                keyAlias = providers.environmentVariable("GPS_EMULATOR_KEY_ALIAS").getOrElse("androiddebugkey")
                keyPassword = providers.environmentVariable("GPS_EMULATOR_KEY_PASSWORD").getOrElse("android")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.robolectric:robolectric:4.17")
}
