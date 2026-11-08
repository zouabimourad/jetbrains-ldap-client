import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.unboundid:unboundid-ldapsdk:7.0.6")

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // Align with the Kotlin stdlib bundled in the target IDE.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.mouradzouabi.ldapclient"
        name = "LDAP Client"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // Secrets are read from environment variables, never stored in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // "1.0.0" -> default (stable) channel, "1.1.0-snapshot.3" -> "snapshot" channel, "1.1.0-eap.1" -> "eap".
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').lowercase().ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Marketplace screenshots: `./gradlew runIdeForScreenshots` starts a sandbox IDE with a dev-only
// "screenshot driver" plugin (src/screenshots) that drives the UI against an in-memory demo
// directory and writes PNG files to docs/screenshots. The driver is never part of buildPlugin.
// ---------------------------------------------------------------------------------------------
val screenshots: SourceSet = sourceSets.create("screenshots") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

// PasswordSafeSettings (used by the driver to avoid the OS keychain) lives in a separate module since 2025.3.
dependencies.add(
    "screenshotsCompileOnly",
    files(provider { intellijPlatform.platformPath.resolve("lib/intellij.platform.credentialStore.impl.jar") }),
)

kotlin.target.compilations.named("screenshots") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

val screenshotDriverJar by tasks.registering(Jar::class) {
    archiveBaseName = "ldap-client-screenshot-driver"
    destinationDirectory = layout.buildDirectory.dir("screenshot-driver")
    from(screenshots.output) { exclude("META-INF/plugin.xml") }
    // The IntelliJ Platform plugin injects the main (patched) plugin.xml into every source set output: use our own.
    from("src/screenshots/descriptor") { into("META-INF") }
}

val prepareScreenshotProject by tasks.registering(Sync::class) {
    from("src/screenshots/acme-directory")
    into(layout.buildDirectory.dir("screenshots/acme-directory"))
    // Keep the IDE project files created by previous runs.
    preserve { include(".idea/**") }
}

val runIdeForScreenshots by intellijPlatformTesting.runIde.registering {
    prepareSandboxTask {
        from(screenshotDriverJar) { into("ldap-client-screenshot-driver/lib") }
    }
    task {
        val outDir = layout.projectDirectory.dir("docs/screenshots").asFile.absolutePath
        val projectDir = layout.buildDirectory.dir("screenshots/acme-directory").get().asFile.absolutePath
        val themes = providers.gradleProperty("screenshotThemes").orElse("Light").get()
        val keepOpen = providers.gradleProperty("screenshotsKeepOpen").orElse("false").get()
        dependsOn(prepareScreenshotProject)
        jvmArgs(
            "-Dldapclient.screenshots.out=$outDir",
            "-Dldapclient.screenshots.themes=$themes",
            "-Dldapclient.screenshots.keepOpen=$keepOpen",
            "-Didea.trust.all.projects=true",
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Dide.newUsersOnboarding=false",
            "-Didea.is.internal=false",
        )
        args(projectDir)
    }
}
