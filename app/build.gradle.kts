// Modified for Streaming Radio by Impostor Labs.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sharedVersionName = providers.gradleProperty("streamingRadioVersionName").get()

android {
    useLibrary("android.car", false)
    namespace = "net.mspanc.twinsenradio"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.mspanc.twinsenradio"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = sharedVersionName
        for ((field, property) in mapOf(
            "PHONE_TRANSFER_API_KEY" to "phoneTransferApiKey",
            "PHONE_TRANSFER_DATABASE_URL" to "phoneTransferDatabaseUrl",
            "PHONE_TRANSFER_HOSTING_URL" to "phoneTransferHostingUrl"
        )) {
            val value = providers.gradleProperty(property).orElse("").get()
                .replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", field, "\"$value\"")
        }
        testInstrumentationRunner = "net.mspanc.twinsenradio.automotive.PrototypeSmokeInstrumentation"
    }

    fun signingPassword(name: String): String? {
        val direct = providers.environmentVariable(name).orNull
        val secretPath = providers.environmentVariable("${name}_FILE").orNull
        require(direct == null || secretPath == null) {
            "Configure a signing password value or its file, not both."
        }
        return direct ?: secretPath?.let {
            val secretFile = file(it)
            require(secretFile.isFile) { "Signing password file is unavailable." }
            secretFile.readText(Charsets.UTF_8).removeSuffix("\n").removeSuffix("\r")
        }
    }
    val uploadStore = providers.environmentVariable("STREAMING_RADIO_KEYSTORE").orNull
    val uploadStorePassword = signingPassword("STREAMING_RADIO_STORE_PASSWORD")
    val uploadAlias = providers.environmentVariable("STREAMING_RADIO_KEY_ALIAS").orNull
    val uploadKeyPassword = signingPassword("STREAMING_RADIO_KEY_PASSWORD")
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
        create("phone") {
            dimension = "platform"
            applicationId = providers.gradleProperty("publicApplicationId").orElse("com.impostorlabs.streamingradio").get()
            versionCode = 21
        }
        create("automotive") {
            dimension = "platform"
            applicationId = providers.gradleProperty("publicApplicationId").orElse("com.impostorlabs.streamingradio").get()
            // Keep AAOS above the paired phone code so the car can replace a phone build.
            versionCode = 22
            minSdk = 28
            buildConfigField("boolean", "IS_AUTOMOTIVE", "true")
        }
    }
    defaultConfig { buildConfigField("boolean", "IS_AUTOMOTIVE", "false") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
        } else if (variant.productFlavors.any { it.second == "phone" }) {
            variant.applicationId.set("com.impostorlabs.streamingradio.phone.debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents.finalizeDsl { dsl ->
    val platforms = listOf("phone", "automotive").map(dsl.productFlavors::getByName)
    require(platforms.all { it.versionName == null && it.versionNameSuffix.isNullOrEmpty() }) {
        "Phone and automotive must inherit the same release version name."
    }
    require(dsl.buildTypes.getByName("release").versionNameSuffix.isNullOrEmpty()) {
        "Release bundles must use the shared version name without a suffix."
    }
    val codes = platforms.map { requireNotNull(it.versionCode) }
    require(codes.all { it in 1..2_100_000_000 } && codes.distinct().size == codes.size) {
        "Assign distinct valid Play version codes to phone and automotive."
    }
}

dependencies {
    val media3 = "1.11.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")

    // Local QR encoding only: no camera, remote QR service or phone SDK.
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.guava:guava:33.7.2-android")

    // Phone only: Cast sender UI and sessions are not part of the AAOS APK.
    add("phoneImplementation", "com.google.android.gms:play-services-cast-framework:22.3.1")

    // Tekst od stacji i decyzje podejmowane na nim to czysta logika. Da sie ja
    // sprawdzic na JVM - bez telefonu i bez czekania, az radio zagra akurat ten
    // utwor, na ktorym cos sie wywalilo.
    testImplementation("junit:junit:4.13.2")
    // The real Media3 extractor uses Android platform utilities during JVM tests.
    testImplementation("org.robolectric:robolectric:4.17")
    // Mockito 5 can mock the final Google Cast SDK client classes for JVM lifecycle tests.
    testImplementation("org.mockito:mockito-core:5.24.0")
    // android.jar ma org.json tylko jako zaslepke - na JVM potrzebny prawdziwy (StationTitleApi)
    testImplementation("org.json:json:20240303")
}



// Keep Android JVM fixtures on canonical Maven Central and reuse their SDK jar.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("maven.repo.local", gradle.gradleUserHomeDir.resolve("robolectric").path)
}
