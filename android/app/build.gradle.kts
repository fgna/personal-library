import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val booksUrl = providers.gradleProperty("booksUrl").orElse("").get()
val sigTestKeystore = layout.projectDirectory.file("sigtest-debug.keystore").asFile
val generateSigTestKeystore by tasks.registering(Exec::class) {
    onlyIf { !sigTestKeystore.exists() }
    commandLine(
        File(System.getProperty("java.home"), "bin/keytool").absolutePath,
        "-genkeypair",
        "-keystore", sigTestKeystore.absolutePath,
        "-storepass", "test1234",
        "-keypass", "test1234",
        "-alias", "sigtest",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "3650",
        "-dname", "CN=Personal Library Signature Test",
    )
}

android {
    namespace = "de.fgna.library"
    compileSdk = 34

    signingConfigs {
        create("sigtest") {
            storeFile = sigTestKeystore
            storePassword = "test1234"
            keyAlias = "sigtest"
            keyPassword = "test1234"
        }
    }

    defaultConfig {
        applicationId = "de.fgna.library"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "BOOKS_URL", "\"${booksUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".sigtest"
            signingConfig = signingConfigs.getByName("sigtest")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    val syncWebAssets by tasks.registering(Copy::class) {
        from(rootProject.projectDir.parentFile) {
            include("mobile.html")
            include("mobile-app.jsx")
            include("data.jsx")
            include("i18n.jsx")
            include("config.js")
            include("android-books-source.js")
            include("android-settings.js")
            include("android-network-diagnostics.js")
            include("android-metadata-diagnostics.js")
            include("android-scan-review-editor.js")
            include("android-author-case-normalizer.js")
            include("android-genre-taxonomy.js")
            include("android-book-editor.js")
            include("android-duplicates.js")
            include("android-duplicate-field-merge.js")
            include("android-ui-copy.js")
        }
        from(rootProject.projectDir.parentFile) {
            include("books.example.json")
            rename("books.example.json", "books.json")
        }
        into(layout.buildDirectory.dir("generated/webAssets/www"))
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
    tasks.named("preBuild").configure { dependsOn(syncWebAssets) }
}

tasks.configureEach {
    if (name == "validateSigningDebug" || name == "packageDebug") {
        dependsOn(generateSigTestKeystore)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
