plugins {
    id("com.android.application")
}

val gdxVersion = "1.14.2"
val gdxNatives = configurations.create("gdxNatives") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

android {
    namespace = "com.rookmender.kxit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mazfons.kxit"
        minSdk = 23
        targetSdk = 35
        versionCode = 12
        versionName = "2.1.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/gdxJniLibs"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/NOTICE*",
                "META-INF/LICENSE*"
            )
        }
    }
}

dependencies {
    implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion") {
        // Keep the SDK 35 project independent of the backend's newer transitive Core.
        exclude(group = "androidx.core", module = "core")
    }
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    add(gdxNatives.name, "com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
}

// libGDX Android's ARM64 runtime is a prebuilt AAR/JAR native library.
// The Android build uses libGDX prebuilt runtime binaries; it has no custom native compile step.
val extractGdxNatives = tasks.register<Copy>("extractGdxNatives") {
    from({ gdxNatives.map { zipTree(it) } })
    include("**/*.so")
    eachFile {
        relativePath = org.gradle.api.file.RelativePath(true, "arm64-v8a", name)
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("generated/gdxJniLibs"))
}

tasks.named("preBuild").configure { dependsOn(extractGdxNatives) }
