import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Подпись release из keystore.properties (вне git). Нет файла - подпись через IDE.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.freeturn.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.freeturn.app"
        // WireGuard GoBackend (com.wireguard.android:tunnel) требует minSdk 24.
        minSdk = 24
        targetSdk = 37
        versionName = "3.5.0" // x-release-please-version
        // Производный от versionName (M*10000+m*100+p) - release-please бампит только строку версии
        versionCode = versionName!!.split(".").let { (ma, mi, pa) ->
            ma.toInt() * 10000 + mi.toInt() * 100 + pa.toInt()
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "FreeTurn Debug")
        }
        release {
            resValue("string", "app_name", "FreeTurn")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // WireGuard tunnel-либа использует java.time, поэтому нужен desugaring.
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.jsch)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.wireguard.tunnel)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.nav.suite)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.koin.androidx.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinxCoroutinesTest)
}

abstract class AssembleControlScript : DefaultTask() {
    @get:InputDirectory
    abstract val srcDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val out = outDir.get().file("free-turn-control.sh").asFile
        out.parentFile.mkdirs()
        val parts = srcDir.get().asFile.listFiles { f -> f.isFile && f.extension == "sh" }
            ?.sortedBy { it.name } ?: emptyList()
        require(parts.isNotEmpty()) { "no .sh modules in ${srcDir.get().asFile}" }
        out.writeText(parts.joinToString("\n") { it.readText().trimEnd('\n') } + "\n")
    }
}

val assembleControlScript = tasks.register<AssembleControlScript>("assembleControlScript") {
    description = "Склеивает server-control/src/*.sh в free-turn-control.sh"
    group = "build"
    srcDir.set(rootProject.layout.projectDirectory.dir("server-control/src"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            assembleControlScript,
            AssembleControlScript::outDir
        )
    }
}
