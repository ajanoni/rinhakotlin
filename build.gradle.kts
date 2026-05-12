import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "9.0.0-beta4"
    application
}

group = "rinhakotlin"
version = "1.0-SNAPSHOT"

val vertxVersion = "4.5.10"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    runtimeOnly("io.netty:netty-transport-native-epoll") {
        artifact { classifier = "linux-x86_64" }
    }
}

application {
    mainClass.set("rinhakotlin.MainKt")
    applicationDefaultJvmArgs = listOf("--add-modules", "jdk.incubator.vector")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "rinhakotlin.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.incubator.vector")
}
