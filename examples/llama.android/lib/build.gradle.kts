plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

val libraryVersion: String = project.findProperty("version") as? String ?: "1.2.0-SNAPSHOT"
val githubUser: String? = project.findProperty("githubUser") as? String
val githubToken: String? = project.findProperty("githubToken") as? String

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
                arguments += "-DLLAMA_OPENSSL=OFF"

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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

// Publishing configuration for GitHub Packages
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.github.creatiwi-ai"
                artifactId = "llama-android"
                version = libraryVersion

                pom {
                    name.set("llama-android")
                    description.set("llama.cpp Android library for on-device LLM inference")
                    url.set("https://github.com/Creatiwi-ai/llama.cpp")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://github.com/Creatiwi-ai/llama.cpp/blob/master/LICENSE")
                        }
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Creatiwi-ai/llama.cpp")
                credentials {
                    username = githubUser ?: System.getenv("GITHUB_ACTOR")
                    password = githubToken ?: System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
