plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

group = "com.thelightphone"

ext["compileSdk"] = 36
ext["minSdk"] = 34
ext["targetSdk"] = 36
ext["jvmTarget"] = "17"
ext["lintVersion"] = "31.12.3"

val localProperties = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

subprojects {
    afterEvaluate {
        plugins.withId("maven-publish") {
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/lightphone/light-sdk")
                        credentials {
                            username = localProperties.getProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
                            password = localProperties.getProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
                        }
                    }
                }
            }
        }
    }
}

// PR gate run by .github/workflows/pr-check.yml. Same coverage as `check`,
// minus the upstream example apps (the iPod tool doesn't depend on them) and
// minus release-variant unit tests, which re-run the debug tests unchanged.
tasks.register("ciCheck") {
    group = "verification"
    description = "Debug unit tests + lint for the iPod tool, SDK and lint rules."
    dependsOn(
        listOf(":tool", ":sdk:client", ":sdk:ui", ":sdk:server", ":sdk:emulator")
            .flatMap { listOf("$it:testDebugUnitTest", "$it:lint") } +
            listOf(":sdk:shared:check", ":lint-rules:check"),
    )
}
