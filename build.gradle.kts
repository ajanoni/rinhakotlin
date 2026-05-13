import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "9.0.0-beta4"
    application
}

group = "rinhakotlin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.register<JavaExec>("buildIndex") {
    group = "application"
    description = "Build IVF index from references.json.gz"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("rinhakotlin.BuildIndexKt")
    jvmArgs("--add-modules", "jdk.incubator.vector", "-Xmx3g")
}