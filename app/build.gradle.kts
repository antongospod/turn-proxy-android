import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
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
        versionName = "3.6.0" // x-release-please-version
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

composeCompiler {
    if (project.findProperty("composeReports") == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
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

/**
 * Тянет нативное ядро из релизов free-turn-proxy в jniLibs (папка вне git).
 * Ассеты релиза без расширения: arm64 - client-android-arm64, armv7 - client-linux-armv7
 * (Go-бинарник linux/arm GOARM=7 работает и на Android); оба кладутся как libfreeturn.so.
 */
abstract class FetchFreeturnCore : DefaultTask() {
    @get:Input
    abstract val repo: Property<String>

    /** "latest" или конкретный тег/версия ("2.1.0", "v2.1.0"). */
    @get:Input
    abstract val version: Property<String>

    /** ABI -> имя ассета в релизе. */
    @get:Input
    abstract val assetNames: MapProperty<String, String>

    @get:Input
    @get:Optional
    abstract val token: Property<String>

    /** Вне build/ - переживает clean, общий на все проекты. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val libs = jniLibsDir.get().asFile
        val stamp = File(libs, ".core-version")
        val installed = if (stamp.isFile) stamp.readText().trim() else null
        val allPresent = assetNames.get().keys.all { File(libs, "$it/libfreeturn.so").isFile }

        val tag = try {
            resolveTag()
        } catch (e: Exception) {
            // Оффлайн со скачанным ядром - не повод ронять сборку
            if (allPresent && installed != null) {
                logger.warn("FreeTurn core: не удалось узнать версию (${e.message}), оставляю $installed")
                return
            }
            throw GradleException("FreeTurn core: не удалось определить версию из ${repo.get()}: ${e.message}", e)
        }

        if (tag == installed && allPresent) {
            didWork = false
            return
        }

        val base = "https://github.com/${repo.get()}/releases/download/$tag"
        val cache = File(cacheDir.get().asFile, tag).apply { mkdirs() }
        val sums = cachedFile(File(cache, "checksums.txt"), "$base/checksums.txt")
            .readLines()
            .mapNotNull { line ->
                val p = line.trim().split(Regex("\\s+"))
                if (p.size == 2) p[1].removePrefix("*") to p[0] else null
            }.toMap()

        assetNames.get().forEach { (abi, asset) ->
            val src = cachedFile(File(cache, asset), "$base/$asset")
            val expected = sums[asset]
                ?: throw GradleException("FreeTurn core: $asset нет в checksums.txt релиза $tag")
            val actual = sha256(src)
            if (!actual.equals(expected, ignoreCase = true)) {
                src.delete()
                throw GradleException("FreeTurn core: sha256 $asset не сошёлся ($actual != $expected)")
            }
            val dst = File(libs, "$abi/libfreeturn.so")
            dst.parentFile.mkdirs()
            src.copyTo(dst, overwrite = true)
        }

        stamp.writeText(tag)
        logger.lifecycle("FreeTurn core: $tag -> ${libs.path}")
    }

    private fun resolveTag(): String {
        val v = version.get().trim()
        if (!v.equals("latest", ignoreCase = true)) return if (v.startsWith("v")) v else "v$v"
        val json = String(
            httpGet("https://api.github.com/repos/${repo.get()}/releases/latest", "application/vnd.github+json"),
            Charsets.UTF_8
        )
        return Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: throw GradleException("нет tag_name в ответе GitHub API")
    }

    private fun cachedFile(dest: File, url: String): File {
        if (dest.isFile && dest.length() > 0) return dest
        val tmp = File(dest.parentFile, "${dest.name}.part")
        tmp.writeBytes(httpGet(url, "application/octet-stream"))
        tmp.renameTo(dest)
        return dest
    }

    private fun httpGet(url: String, accept: String): ByteArray {
        var current = URI(url).toURL()
        repeat(6) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 300_000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "freeturn-android-build")
                // Токен только на github.com - на редиректе в CDN он ломает подписанный URL
                val t = token.orNull
                if (!t.isNullOrBlank() && current.host.endsWith("github.com")) {
                    setRequestProperty("Authorization", "Bearer $t")
                }
            }
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> return conn.inputStream.use { it.readBytes() }
                    301, 302, 303, 307, 308 -> {
                        val loc = conn.getHeaderField("Location")
                            ?: throw GradleException("редирект без Location на $current")
                        current = URI(current.toURI().resolve(loc).toString()).toURL()
                    }
                    else -> throw GradleException("HTTP $code на $current")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw GradleException("слишком много редиректов на $url")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val fetchFreeturnCore = tasks.register<FetchFreeturnCore>("fetchFreeturnCore") {
    description = "Качает нативное ядро из релизов free-turn-proxy в jniLibs"
    group = "build"
    repo.set(providers.gradleProperty("freeturnCoreRepo").orElse("samosvalishe/free-turn-proxy"))
    version.set(
        providers.gradleProperty("freeturnCore")
            .orElse(providers.environmentVariable("FREETURN_CORE_VERSION"))
            .orElse("latest")
    )
    assetNames.set(
        mapOf(
            "arm64-v8a" to "client-android-arm64",
            "armeabi-v7a" to "client-linux-armv7"
        )
    )
    token.set(providers.environmentVariable("GITHUB_TOKEN"))
    cacheDir.set(layout.dir(provider { File(gradle.gradleUserHomeDir, "caches/freeturn-core") }))
    jniLibsDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
    // Версия резолвится в рантайме - актуальность решает stamp-файл внутри таски
    outputs.upToDateWhen { false }
}

// В debug ядро подкладывается руками, поэтому по умолчанию качаем только на release.
// matching, а не named - таски вариантов AGP создаёт позже конфигурации скрипта.
val coreFetchHook = when (providers.gradleProperty("coreFetch").orNull ?: "release") {
    "none" -> null
    "all" -> "preBuild"
    else -> "preReleaseBuild"
}
if (coreFetchHook != null) {
    tasks.matching { it.name == coreFetchHook }.configureEach { dependsOn(fetchFreeturnCore) }
}
