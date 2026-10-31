plugins {
    id("com.android.application")
}

android {
    namespace = "com.vanillara.redalert"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.vanillara.redalert"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.setSrcDirs(
                listOf(
                    "src/main/java",
                    "../third_party/SDL/android-project/app/src/main/java"
                )
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            // Release signing is intentionally left to the person distributing the
            // build. The source port ships as a locally built debug APK.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}

tasks.register("printDebugApkPath") {
    doLast {
        println(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.absolutePath)
    }
}
