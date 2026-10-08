plugins {
    id("fabric-loom") version "1.13.6"
    `maven-publish`
}

group = "com.armaturemc"
version = providers.gradleProperty("modVersion").orElse("0.8.5-preview").get()
base { archivesName.set("armature-client-fabric-1.21.8") }
repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com/releases/") { content { includeGroup("com.terraformersmc") } }
}
dependencies {
    minecraft("com.mojang:minecraft:1.21.8")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.136.1+1.21.8")
    modCompileOnly("com.terraformersmc:modmenu:15.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}
val standaloneSources = file("shared/src/main/java").isDirectory
val sharedResourceRoot = if (standaloneSources) file("shared/src/main/resources")
    else file("../armature-renderer-native/src/main/resources")
// The same platform-free samplers compile on both sides; no duplicate animation implementation.
sourceSets.main {
    if (standaloneSources) java.srcDir("shared/src/main/java")
    else {
        java.srcDir("../armature-client-protocol/src/main/java")
        java.srcDir("../armature-renderer-native/src/main/java")
        java.srcDir("../armature-core/src/main/java")
        java.srcDir("../armature-renderer-api/src/main/java")
    }
    java.include("com/armaturemc/client/**", "com/armaturemc/renderer/internal/animation/**",
        "com/armaturemc/renderer/internal/pose/**", "com/armaturemc/renderer/internal/compile/CompiledModel.java",
        "com/armaturemc/renderer/internal/compile/ArmatureBoneRole.java",
        "com/armaturemc/renderer/internal/compile/BlockbenchHierarchyCompiler.java",
        "com/armaturemc/renderer/internal/bbmodel/NativeModelLimits.java",
        "com/armaturemc/renderer/internal/diagnostics/ModelDiagnostic.java",
        "com/armaturemc/renderer/internal/asset/NativeModelSource.java",
        "com/armaturemc/core/animation/AdditivePose.java", "com/armaturemc/core/motion/DampedSpring.java",
        "com/armaturemc/renderer/internal/runtime/NativeMotionSampler.java",
        "com/armaturemc/renderer/internal/runtime/NativeBonePhysics.java",
        "com/armaturemc/renderer/api/ArmatureMotionSettings.java", "com/armaturemc/renderer/api/ArmatureSwaySettings.java",
        "com/armaturemc/renderer/api/ArmaturePhysicsSettings.java", "com/armaturemc/renderer/api/RenderMotionInput.java")
}
sourceSets.test {
    java.srcDir(if (standaloneSources) "shared/src/test/java" else "../armature-client-protocol/src/test/java")
}
tasks.processResources {
    from(sharedResourceRoot) { include("armature/body-parts-v2.json") }
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
tasks.test { useJUnitPlatform() }
tasks.jar {
    from("LICENSE")
    from("NOTICE.md") { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.named<Jar>("sourcesJar") {
    from("LICENSE")
    from("NOTICE.md") { into("META-INF") }
}
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = base.archivesName.get()
            pom {
                name.set("Armature Client Mod")
                description.set("Optional Fabric first-person renderer for Armature")
                url.set("https://github.com/Cryxow/ArmatureClientMod")
                licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit") } }
                scm { url.set("https://github.com/Cryxow/ArmatureClientMod") }
            }
        }
    }
}

// Export only client files and the exact shared sources selected above. Never ship
// the plugin checkout, local logs, Minecraft downloads or generated server packs.
tasks.register("exportStandalone") {
    group = "distribution"
    description = "Export a self-contained source tree for the separate public client repository."
    doLast {
        val destination = providers.gradleProperty("standaloneDir").orNull?.let { file(it) }
            ?: layout.buildDirectory.dir("standalone/ArmatureClientMod").get().asFile
        require(!destination.exists() || destination.listFiles().isNullOrEmpty()) {
            "Export destination must be absent or empty: $destination"
        }
        val ownSources = file("src/main/java").toPath().toAbsolutePath().normalize()
        val ownTests = file("src/test/java").toPath().toAbsolutePath().normalize()
        copy {
            from(projectDir) {
                include("src/**", "docs/**", ".github/**", "build.gradle.kts", "settings.gradle.kts",
                    "gradle.properties", "README.md", "CONTRIBUTING.md", "LICENSE", "NOTICE.md",
                    ".gitignore", ".gitattributes", "gradle/wrapper/LICENSE")
            }
            into(destination)
        }
        // Gradle/Ant's default excludes omit these names even with explicit includes.
        for (name in listOf(".gitignore", ".gitattributes")) {
            file(name).copyTo(destination.resolve(name), overwrite = true)
        }
        copy {
            from(sourceSets.main.get().java) {
                exclude { it.file.toPath().toAbsolutePath().normalize().startsWith(ownSources) }
                filter { line: String -> line.trimEnd('\r') }
            }
            into(destination.resolve("shared/src/main/java"))
        }
        copy {
            from(sourceSets.test.get().java) {
                exclude { it.file.toPath().toAbsolutePath().normalize().startsWith(ownTests) }
            }
            into(destination.resolve("shared/src/test/java"))
        }
        copy {
            from(sharedResourceRoot) { include("armature/body-parts-v2.json") }
            into(destination.resolve("shared/src/main/resources"))
        }
        val wrapperRoot = if (standaloneSources) projectDir else projectDir.parentFile
        copy {
            from(wrapperRoot) { include("gradlew", "gradlew.bat", "gradle/wrapper/**") }
            into(destination)
        }
        require(destination.resolve("gradle/wrapper/gradle-wrapper.jar").isFile) { "Missing Gradle wrapper" }
        require(destination.resolve("LICENSE").isFile) { "Missing distribution license" }
        require(destination.resolve("shared/src/main/java/com/armaturemc/client/protocol/ClientProtocol.java").isFile) {
            "Missing client protocol in standalone export"
        }
        logger.lifecycle("Standalone client sources exported to $destination")
    }
}
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "armature_client_test"
        enableGameTests = false
        enableClientGameTests = true
        eula = false
    }
}
val armatureTestServer = providers.gradleProperty("armatureTestServer")
if (armatureTestServer.isPresent) {
    loom.runs.named("clientGameTest") {
        vmArg("-Darmature.client.testServer=" + armatureTestServer.get())
        // A Paper/Folia peer does not run Fabric's test packet accounting hooks.
        vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
        if (providers.gradleProperty("armatureTestArmor").orNull == "true") {
            vmArg("-Darmature.client.testArmor=true")
        }
        if (providers.gradleProperty("armatureTestFraming").orNull == "true") {
            vmArg("-Darmature.client.testFraming=true")
        }
        providers.gradleProperty("armatureTestPack").orNull?.let {
            vmArg("-Darmature.client.testPack=" + it)
        }
    }
}
