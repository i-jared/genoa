plugins {
    id("com.google.gms.google-services") version "4.4.3" apply false
    id("com.google.firebase.crashlytics") version "3.0.6" apply false
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

val ktfmt by configurations.creating {
    isTransitive = false
    attributes { attribute(org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE, objects.named(org.gradle.api.attributes.Bundling.SHADOWED)) }
}
dependencies { ktfmt("com.facebook:ktfmt:0.59") }
tasks.register<JavaExec>("formatKotlin") {
    classpath = ktfmt
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--kotlinlang-style")
    args(fileTree("app/src") { include("**/*.kt") }.files.map { it.absolutePath })
}
