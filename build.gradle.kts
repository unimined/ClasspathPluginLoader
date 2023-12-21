import java.net.URI

plugins {
    java
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

version = if (project.hasProperty("version_snapshot")) project.properties["version"] as String + "-SNAPSHOT" else project.properties["version"] as String
group = project.properties["maven_group"] as String

base {
    archivesName.set(project.properties["archives_base_name"] as String)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    // spigot maven
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

val shadow by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

@Suppress("VulnerableLibrariesLocal")
dependencies {
    implementation("org.bukkit:bukkit:1.12.2-R0.1-SNAPSHOT")
    shadow("org.ow2.asm:asm:9.5")
    shadow("org.ow2.asm:asm-tree:9.5")
}

tasks.compileJava {
    options.encoding = "UTF-8"

    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.jar {
    manifest {
        attributes(
                "Premain-Class" to "xyz.wagyourtail.unimined.cpl.CPLAgent",
                "Main-Class" to "xyz.wagyourtail.unimined.cpl.CPLAgent",
                "Implementation-Version" to project.version,
        )
    }
}

tasks.shadowJar {
    configurations = listOf(shadow)
    relocate("org.objectweb", "xyz.wagyourtail.unimined.cpl.shadow.org.objectweb")
}

publishing {
    repositories {
        maven {
            name = "WagYourMaven"
            url = if (project.hasProperty("version_snapshot")) {
                URI.create("https://maven.wagyourtail.xyz/snapshots/")
            } else {
                URI.create("https://maven.wagyourtail.xyz/releases/")
            }
            credentials {
                username = project.findProperty("mvn.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("mvn.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group as String
            artifactId = project.properties["archives_base_name"] as String? ?: project.name
            version = project.version as String

            artifact(tasks["jar"]) {}
            artifact(tasks["shadowJar"]) {
                classifier = "all"
            }
        }
    }
}