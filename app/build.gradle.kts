plugins {
    id("com.android.application")
}

android {
    namespace = "com.voiceclear.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voiceclear.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 目标设备为华为/荣耀（ARM64），保留 arm64 与 armv7 以覆盖老机型。
        // x86/x86_64 仅模拟器需要，去掉可省约 71 MB 包体。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    /**
     * 构建后校验 APK 权限。
     *
     * 背景：曾因清单漏声明 INTERNET 权限，导致模型下载在真机上全部失败，
     * 而报错是 "Permission denied (missing INTERNET permission?)"——不翻诊断日志
     * 很难定位。这里把它变成构建期硬门禁。
     */
    val checkApkPermissions by tasks.registering {
        dependsOn("assembleDebug")
        doLast {
            val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
            if (!apk.exists()) {
                throw GradleException("找不到 APK: " + apk.absolutePath)
            }
            val script = rootProject.file("tools/check_permissions.py")
            val process = ProcessBuilder(
                "python3", script.absolutePath, apk.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            logger.lifecycle(output)
            if (exitCode != 0) {
                throw GradleException(
                    "APK 权限校验未通过（退出码 " + exitCode + "），请补齐必需权限后重新构建。",
                )
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Linux arm64 需要显式声明原生库目录，Robolectric 会从中加载 conscrypt 等组件。
                it.systemProperty(
                    "robolectric.native.dir",
                    "${project.projectDir}/src/test/resources/native",
                )
                it.systemProperty("robolectric.logging", "stdout")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")

    // sherpa-onnx 端侧离线识别（Apache-2.0）。
    //
    // 使用**官方 AAR**（来自 GitHub Release v2.0.1），而非第三方 Maven 重新打包版：
    // 第三方 jar（com.litongjava:sherpa-onnx-java-api）的 LibraryUtils 是**桌面平台**的
    // 加载器，会读 user.home 拼 $HOME/lib/<os>-<arch>/ 并解压 native 库；
    // Android 上 user.home 为 null，路径退化成 /lib/linux-aarch64，
    // 应用无权创建该目录，于是抛 "Unable to create directory: /lib/linux-aarch64"。
    // 官方 AAR 直接用 System.loadLibrary，从 APK 的 lib/<abi>/ 加载，无此问题。
    //
    // AAR 内含全部 ABI 的 native 库，这里只保留目标设备需要的两种。
    implementation(files("../libs/sherpa-onnx-2.0.1.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 4.18-beta-1 起原生运行时才包含 Linux arm64(aarch64) 库，本机为 aarch64 架构。
    testImplementation("org.robolectric:robolectric:4.18-beta-1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
