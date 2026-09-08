import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * Release signing is driven either by a local `keystore.properties` (never
 * committed) or by the environment variables the GitHub Actions release
 * workflow exports after decoding KEYSTORE_BASE64. When neither is present the
 * release variant simply stays unsigned so that `assembleRelease` still
 * configures on a machine without secrets.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey)

val storeFilePath = signingValue("storeFile", "KEYSTORE_PATH")
val storePasswordValue = signingValue("storePassword", "KEYSTORE_PASSWORD")
val keyAliasValue = signingValue("keyAlias", "KEY_ALIAS")
val keyPasswordValue = signingValue("keyPassword", "KEY_PASSWORD")
val hasSigningConfig = storeFilePath != null && storePasswordValue != null &&
    keyAliasValue != null && keyPasswordValue != null

android {
    namespace = "com.jarvis.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Baked-in default gateway. Override at build time with
        // `-PjarvisGatewayUrl=https://jarvis.example.com`; leaving it empty makes
        // the app show its first-run setup screen instead.
        buildConfigField(
            "String",
            "DEFAULT_GATEWAY_URL",
            "\"${project.findProperty("jarvisGatewayUrl") ?: ""}\""
        )
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // PRs should fail on real problems but not on the informational noise
        // that a WebView-hosting app inevitably trips.
        warningsAsErrors = false
        abortOnError = true
        // Printed to stdout so a CI failure shows the findings themselves rather
        // than only a Gradle stack trace.
        textReport = true
        xmlReport = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
}
