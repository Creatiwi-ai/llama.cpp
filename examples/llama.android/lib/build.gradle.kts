plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 35

    // Use NDK version compatible with memorial project
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 26  // Lower to match memorial project

        // Handle missing "environment" flavor dimension from consuming modules
        missingDimensionStrategy("environment", "dev", "prod")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_CURL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=OFF"
                arguments += "-DGGML_CPU_ALL_VARIANTS=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"

                // Disable KleidiAI to avoid FetchContent issues
                arguments += "-DGGML_CPU_KLEIDIAI=OFF"
                arguments += "-DGGML_OPENMP=OFF"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
