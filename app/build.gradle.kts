import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    useLibrary("android.car", false)
    namespace = "net.mspanc.twinsenradio"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.mspanc.twinsenradio"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.0-rc4"
        testInstrumentationRunner = "net.mspanc.twinsenradio.automotive.PrototypeSmokeInstrumentation"
    }

    val uploadStore = providers.environmentVariable("STREAMING_RADIO_KEYSTORE").orNull
    val uploadStorePassword = providers.environmentVariable("STREAMING_RADIO_STORE_PASSWORD").orNull
    val uploadAlias = providers.environmentVariable("STREAMING_RADIO_KEY_ALIAS").orNull
    val uploadKeyPassword = providers.environmentVariable("STREAMING_RADIO_KEY_PASSWORD").orNull
    val uploadValues = listOf(uploadStore,uploadStorePassword,uploadAlias,uploadKeyPassword)
    require(uploadValues.all { it == null } || uploadValues.all { !it.isNullOrBlank() }) {
        "Configure all four STREAMING_RADIO signing environment variables, or leave all unset for an unsigned candidate."
    }
    val uploadSigning = if(uploadValues.all { !it.isNullOrBlank() }) signingConfigs.create("publicUpload") {
        storeFile = file(uploadStore!!)
        storePassword = uploadStorePassword
        keyAlias = uploadAlias
        keyPassword = uploadKeyPassword
    } else null

    buildTypes {
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-local"
            val localDefault = providers.gradleProperty("localDefaultPlaylistUrl").orElse("").get()
                .replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "DEFAULT_PLAYLIST_URL", "\"$localDefault\"")
            buildConfigField("boolean", "ENABLE_ITUNES_ARTWORK", "true")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            val publicStarter = providers.gradleProperty("publicStarterPlaylistUrl").orElse("").get()
            require(publicStarter.isEmpty() || publicStarter.startsWith("https://")) { "Public starter playlist requires HTTPS" }
            val escapedStarter=publicStarter.replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "DEFAULT_PLAYLIST_URL", "\"$escapedStarter\"")
            signingConfig = uploadSigning
            // Apple's promotional artwork conditions are not implemented in the public UI.
            buildConfigField("boolean", "ENABLE_ITUNES_ARTWORK", "false")
        }
    }

    flavorDimensions += "platform"
    productFlavors {
        create("phone") { dimension = "platform" }
        create("automotive") {
            dimension = "platform"
            applicationId = providers.gradleProperty("publicApplicationId").orElse("com.impostorlabs.streamingradio").get()
            minSdk = 28
            buildConfigField("boolean", "IS_AUTOMOTIVE", "true")
        }
    }
    defaultConfig { buildConfigField("boolean", "IS_AUTOMOTIVE", "false") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.register("writeAutomotiveReleaseInventory") {
    doLast {
        val components=configurations.getByName("automotiveReleaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents.mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { mapOf("group" to it.group,"name" to it.module,"version" to it.version,
                "purl" to "pkg:maven/${it.group}/${it.module}@${it.version}") }
            .sortedBy { it["purl"] }
        val output=layout.buildDirectory.file("reports/release/runtime-components.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(components)))
    }
}

// Keep local testing isolated from the public package identity.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (variant.productFlavors.any { it.second == "automotive" }) {
            variant.applicationId.set("com.impostorlabs.streamingradio.debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.guava:guava:33.7.2-android")

    // Tekst od stacji i decyzje podejmowane na nim to czysta logika. Da sie ja
    // sprawdzic na JVM - bez telefonu i bez czekania, az radio zagra akurat ten
    // utwor, na ktorym cos sie wywalilo.
    testImplementation("junit:junit:4.13.2")
    // android.jar ma org.json tylko jako zaslepke - na JVM potrzebny prawdziwy (StationTitleApi)
    testImplementation("org.json:json:20240303")
}


