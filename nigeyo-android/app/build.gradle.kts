plugins {
    id("com.android.application") version "9.5.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "jp.nigeyo.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.nigeyo.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        val host = providers.gradleProperty("nigeyoRegistrationHost").orElse("").get()
        require(host.isEmpty() || host.matches(Regex("[a-z0-9.-]+")))
        buildConfigField("String", "REGISTRATION_HOST", "\"$host\"")
    }
}

abstract class BundleGuideAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedAssets: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val evacuationSites: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val guideContracts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun bundle() {
        files.sync {
            from(sharedAssets) {
                include("cards.json", "languages/*.json")
                include("wallpapers/earthquake.png", "wallpapers/tsunami.png")
                exclude("languages/manifest.json")
            }
            from(evacuationSites)
            from(guideContracts)
            into(outputDirectory)
        }
    }
}

val bundleGuideAssets = tasks.register<BundleGuideAssets>("bundleGuideAssets") {
    sharedAssets.set(rootProject.layout.projectDirectory.dir("../assets"))
    evacuationSites.set(rootProject.layout.projectDirectory.file(
        "../nigeyo-ios/Nigeyo/Resources/evacuation-sites.json",
    ))
    guideContracts.from(
        rootProject.layout.projectDirectory.file("../contracts/required-string-keys.json"),
        rootProject.layout.projectDirectory.file("../nigeyo-ios/Nigeyo/Resources/notification-regions-national.json"),
        rootProject.layout.projectDirectory.file("../nigeyo-ios/Nigeyo/Resources/notification-region-earthquake-boundaries.deflate"),
        rootProject.layout.projectDirectory.file("../nigeyo-ios/Nigeyo/Resources/notification-region-municipality-boundaries.deflate"),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/guideAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            bundleGuideAssets,
            BundleGuideAssets::outputDirectory,
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
