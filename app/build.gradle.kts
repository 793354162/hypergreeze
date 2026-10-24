import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.hypergreeze"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hypergreeze"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("boolean", "IS_MODULE", "true")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                // Resolved against the module dir: keystore.properties uses the conventional
                // app-relative "../keystore/hypergreeze.jks" form.
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ""
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Xposed API 102: compileOnly because the framework provides these classes at runtime.
    // The official artifact (io.github.libxposed:api:102.0.0) is an AAR declaring minCompileSdk=37,
    // which no public SDK platform provides yet, so the classes.jar extracted from that exact
    // artifact is vendored at libs/ (see docs/BUILD_AND_SIGN.md, verified by CI sha256 check).
    compileOnly(files(rootProject.file("libs/libxposed-api-102.jar")))
    compileOnly(libs.libxposed.annotation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)

    debugImplementation(libs.compose.ui.tooling)

    // Pure-JVM tests for the policy layer. The real org.json artifact replaces the android.jar
    // stub so PolicyCodec runs exactly as it does inside system_server.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
