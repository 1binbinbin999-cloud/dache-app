import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dache.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dache.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.2"
    }

    // BuildConfig.VERSION_NAME 供原生桥 getAppVersion() 使用；AGP 8 默认行为之外再显式打开一次。
    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // 只有当本次调用确实要打 release 包时，才要求四个签名环境变量齐全；
        // 跑 debug、tasks、lint 等不触发，避免配置阶段就把所有 Gradle 调用都堵死。
        val releaseRequested = gradle.startParameter.taskNames.any { taskName ->
            taskName.contains("Release", ignoreCase = true) ||
                taskName in setOf("build", "assemble", "bundle")
        }
        if (releaseRequested) {
            create("release") {
                val keystoreFile = System.getenv("DACHE_KEYSTORE_FILE")
                val keystorePassword = System.getenv("DACHE_KEYSTORE_PASSWORD")
                val keyAliasValue = System.getenv("DACHE_KEY_ALIAS")
                val keyPasswordValue = System.getenv("DACHE_KEY_PASSWORD")
                val missing = listOf(
                    "DACHE_KEYSTORE_FILE" to keystoreFile,
                    "DACHE_KEYSTORE_PASSWORD" to keystorePassword,
                    "DACHE_KEY_ALIAS" to keyAliasValue,
                    "DACHE_KEY_PASSWORD" to keyPasswordValue
                ).filter { it.second.isNullOrBlank() }.map { it.first }
                if (missing.isNotEmpty()) {
                    // 缺任何一个都明确报错退出，绝不悄悄打出未签名包。
                    throw GradleException(
                        "Release 签名缺少环境变量：${missing.joinToString("、")}。" +
                            "请在 CI Secrets 里配齐 DACHE_KEYSTORE_FILE、DACHE_KEYSTORE_PASSWORD、" +
                            "DACHE_KEY_ALIAS、DACHE_KEY_PASSWORD 四个变量后再打 release 包。"
                    )
                }
                // keystore 文件本身不入库：CI 从 Secrets 还原到临时路径后把路径传进来。
                // keystore 是 PKCS12 格式（openssl 生成），必须显式声明，否则 JDK 默认类型可能认不出。
                storeType = "PKCS12"
                storeFile = project.file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 非 release 调用时 signingConfigs 里没有 "release"，这里就是 null，release 构建则由上面的
            // 环境变量检查兜底（缺变量时配置阶段已抛 GradleException，不会走到打包）。
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // WebViewAssetLoader（androidx.webkit 1.5.0+ 才有，用 1.8.0）
    implementation("androidx.webkit:webkit:1.8.0")
    // EncryptedSharedPreferences / MasterKey：MasterKey 类在 1.0.0 里不存在，
    // 是 1.1.0-alpha01 才引入的；1.1.0 已是稳定版（Google Maven metadata 确认），
    // 之前锁 1.0.0 导致 MainActivity.kt 里 MasterKey 找不到，编译失败，故升到 1.1.0。
    implementation("androidx.security:security-crypto:1.1.0")
}

// 构建时把 ../web/（相对本 build 脚本所在 app/ 目录是 ../../web/）的全部内容同步进
// app/src/main/assets/，index.html 落在 assets 根目录，assets/ 子目录原样保留，
// 保证 APK 里的 bundle 与仓库里的 web/ 一致。用 Sync 而不用 Copy：web/ 里删掉的文件，
// assets/ 里也会同步删掉，不留过期文件。
tasks.register<Sync>("syncWebAssets") {
    description = "把 ../web/ 同步到 app/src/main/assets/（APK 内 bundle 与仓库 web/ 一致）"
    group = "build"
    from("../../web")
    into("src/main/assets")
    doFirst {
        val webDir = project.file("../../web")
        require(webDir.isDirectory) { "找不到网页 bundle 目录：${webDir.absolutePath}" }
    }
}

// 任何变体打包前都先同步 bundle。
tasks.named("preBuild") {
    dependsOn("syncWebAssets")
}
