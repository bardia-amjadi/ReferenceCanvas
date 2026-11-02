plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.referencecanvas"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.referencecanvas"
        minSdk = 26
        targetSdk = 35
        // CI passes real values for releases: -PrcVersionName=1.2.3 -PrcVersionCode=<build number>
        versionCode = (project.findProperty("rcVersionCode") as String?)?.toIntOrNull() ?: 6
        versionName = (project.findProperty("rcVersionName") as String?) ?: "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes only from the environment (CI secrets) or an untracked local file; nothing is committed.
    // Without all four values the release build is simply unsigned, and the release workflow refuses to call it signed.
    signingConfigs {
        val localProps = rootProject.file("keystore.properties").takeIf { it.exists() }
            ?.let { file -> java.util.Properties().apply { file.inputStream().use { load(it) } } }
        fun setting(env: String, key: String): String? =
            (System.getenv(env) ?: localProps?.getProperty(key))?.takeIf { it.isNotBlank() }
        val storePath = setting("RC_KEYSTORE_PATH", "storeFile")
        val storePass = setting("RC_KEYSTORE_PASSWORD", "storePassword")
        val alias = setting("RC_KEY_ALIAS", "keyAlias")
        val keyPass = setting("RC_KEY_PASSWORD", "keyPassword")
        if (storePath != null && storePass != null && alias != null && keyPass != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing is configured in the release workflow (Stage 6); never commit keystores.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    // Exported Room schemas are used by migration tests.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.work.runtime)
    implementation(libs.media3.ui)
    implementation(libs.androidx.exifinterface)

    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
