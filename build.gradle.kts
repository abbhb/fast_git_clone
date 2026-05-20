import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.spring.dependency-management") version "1.1.3"
}

group = "com.tencent.bk.ci"
version = file("version.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "1.0.1" }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation("com.tencent.devops.ci-plugins:java-plugin-sdk:1.1.9")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.68")
    implementation(kotlin("stdlib-jdk8"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

dependencyManagement {
    dependencies {
        dependency("com.fasterxml.jackson:jackson-bom:2.12.3")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.tencent.bk.devops.atom.AtomRunner"
    }
}

tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("fast_git_clone")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.register<Copy>("copyTaskJson") {
    dependsOn(tasks.shadowJar)
    from("task.json")
    from(tasks.shadowJar.map { it.archiveFile })
    into(layout.buildDirectory.dir("package/fast_git_clone"))
}

tasks.register<Zip>("package") {
    dependsOn("copyTaskJson")
    destinationDirectory.set(layout.buildDirectory.dir("out"))
    archiveFileName.set("fast_git_clone.zip")
    from(layout.buildDirectory.dir("package/fast_git_clone"))
}

tasks.register("verifyPackage") {
    dependsOn("package")
    doLast {
        val zipFile = layout.buildDirectory.file("out/fast_git_clone.zip").get().asFile
        check(zipFile.exists()) { "package zip does not exist: ${zipFile.absolutePath}" }
        val jarFile = layout.buildDirectory.file("libs/fast_git_clone.jar").get().asFile
        check(jarFile.exists()) { "jar does not exist: ${jarFile.absolutePath}" }
    }
}
