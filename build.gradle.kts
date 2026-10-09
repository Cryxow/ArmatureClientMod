import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.api.fabricapi.FabricApiExtension
import java.util.Properties

plugins {
    java
    id("net.fabricmc.fabric-loom-remap") version "1.17.21" apply false
    id("net.fabricmc.fabric-loom") version "1.17.21" apply false
    `maven-publish`
}

group = "com.armaturemc"
version = providers.gradleProperty("modVersion").orElse("0.9.1-preview").get()
val minecraftVersion = providers.gradleProperty("minecraftVersion").orElse("1.21.8").get()
val versionMatrix = Properties().apply { file("gradle/minecraft-versions.properties").inputStream().use { load(it) } }
require(versionMatrix.containsKey("$minecraftVersion.api")) { "Unsupported Minecraft target: $minecraftVersion" }
val unobfuscated = minecraftVersion.startsWith("26.")
val submitted = minecraftVersion != "1.21.8"
val modern = minecraftVersion == "1.21.11" || unobfuscated
val next = minecraftVersion == "26.2"
apply(plugin = if (unobfuscated) "net.fabricmc.fabric-loom" else "net.fabricmc.fabric-loom-remap")
val loom = extensions.getByType<LoomGradleExtensionAPI>()
layout.buildDirectory.set(layout.projectDirectory.dir("build/$minecraftVersion"))
base { archivesName.set("armature-client-fabric-$minecraftVersion") }
repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com/releases/") { content { includeGroup("com.terraformersmc") } }
}
dependencies {
    add("minecraft", "com.mojang:minecraft:$minecraftVersion")
    if (!unobfuscated) add("mappings", loom.officialMojangMappings())
    add(if (unobfuscated) "implementation" else "modImplementation", "net.fabricmc:fabric-loader:0.19.5")
    add(if (unobfuscated) "implementation" else "modImplementation",
        "net.fabricmc.fabric-api:fabric-api:" + versionMatrix.getProperty("$minecraftVersion.api"))
    add(if (unobfuscated) "compileOnly" else "modCompileOnly",
        "com.terraformersmc:modmenu:" + versionMatrix.getProperty("$minecraftVersion.menu"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.ow2.asm:asm-tree:9.9.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(if (unobfuscated) 25 else 21)
    options.encoding = "UTF-8"
}
val standaloneSources = file("shared/src/main/java").isDirectory
val sharedResourceRoot = if (standaloneSources) file("shared/src/main/resources")
    else file("../armature-renderer-native/src/main/resources")
// The same platform-free samplers compile on both sides; no duplicate animation implementation.
val mainSources = sourceSets.main.get()
mainSources.apply {
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
// Shared sources remain authored once. Narrow, documented API renames are
// applied to generated sources; rendering changes live in versioned adapters.
val selectedOverlays = buildList {
    if (submitted) add("submit")
    if (unobfuscated) add("calendar")
    if (next) add("next")
}
val originalSourceRoots = mainSources.java.srcDirs.toList()
val generatedSources = layout.buildDirectory.dir("generated/main/java")
val prepareMinecraftSources = tasks.register("prepareMinecraftSources") {
    inputs.files(originalSourceRoots)
    inputs.dir("src/versioned")
    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(generatedSources)
    doLast {
        val sources = linkedMapOf<String, File>()
        for (root in originalSourceRoots) {
            if (!root.isDirectory) continue
            for (source in fileTree(root).matching { include("**/*.java") }) {
                val relative = source.relativeTo(root).invariantSeparatorsPath
                if (mainSources.java.includes.any { org.apache.tools.ant.types.selectors.SelectorUtils.matchPath(it, relative) })
                    sources[relative] = source
            }
        }
        for (overlay in selectedOverlays) {
            val root = file("src/versioned/$overlay/java")
            for (source in fileTree(root).matching { include("**/*.java") })
                sources[source.relativeTo(root).invariantSeparatorsPath] = source
        }
        if (unobfuscated) sources.remove("com/armaturemc/client/mixin/GameRendererAccessor.java")
        val output = generatedSources.get().asFile
        // This directory is owned by this task, inside the target's build directory.
        require(output.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        delete(output)
        for ((relative, source) in sources) {
            var text = source.readText()
            if (submitted) text = text.replace("MultiBufferSource.BufferSource", "SubmitNodeCollector")
                .replace("MultiBufferSource", "SubmitNodeCollector")
                .replace("minecraft.getItemRenderer()", "minecraft.getItemModelResolver()")
                .replace("net.minecraft.client.resources.PlayerSkin", "net.minecraft.world.entity.player.PlayerSkin")
                .replace("PlayerSkin.Model.SLIM", "net.minecraft.world.entity.player.PlayerModelType.SLIM")
                .replace("getSkin().texture()", "getSkin().body().texturePath()")
                .replace("getModelManager().getAtlas(", "getAtlasManager().getAtlasOrThrow(")
                .replace("layer.model(), item,", "layer.model(), net.minecraft.util.Unit.INSTANCE, item,")
                .replace("buffers, light, player.getSkin().body().texturePath());",
                    "buffers, light, player.getSkin().body().texturePath(), 0, 1);")
            if (modern) text = text.replace("net.minecraft.client.renderer.RenderType", "net.minecraft.client.renderer.rendertype.RenderType")
                .replace("RenderType::armorCutoutNoCull", "net.minecraft.client.renderer.rendertype.RenderTypes::armorCutoutNoCull")
                .replace("RenderType.entityCutoutNoCull", "net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull")
                .replace("RenderType.entityTranslucent", "net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent")
            if (modern) text = text.replace("ResourceLocation", "Identifier").replace("BlockGetter", "Level")
            if (unobfuscated) text = text.replace("GuiGraphics", "GuiGraphicsExtractor")
                .replace("void render(GuiGraphicsExtractor", "void extractRenderState(GuiGraphicsExtractor")
                .replace("super.render(graphics,", "super.extractRenderState(graphics,")
                .replace("drawCenteredString", "centeredText")
                .replace("PayloadTypeRegistry.playS2C()", "PayloadTypeRegistry.clientboundPlay()")
                .replace("PayloadTypeRegistry.playC2S()", "PayloadTypeRegistry.serverboundPlay()")
            if (next) text = text.replace("\"renderHandsWithItems\"", "\"submitHandsWithItems\"")
                .replace("\"renderArmWithItem\"", "\"submitArmWithItem\"")
                .replace("minecraft.setScreen(", "minecraft.gui.setScreen(")
                .replace("client.getOverlay()", "client.gui.overlay()")
                .replace("client.options.hideGui", "client.gui.hud.isHidden()")
                .replace("getMainCamera()", "mainCamera()")
            val target = output.resolve(relative)
            target.parentFile.mkdirs()
            target.writeText(text)
        }
    }
}
mainSources.java.setSrcDirs(listOf(generatedSources))
tasks.compileJava { dependsOn(prepareMinecraftSources) }
tasks.named<Jar>("sourcesJar") { dependsOn(prepareMinecraftSources) }
sourceSets.test {
    java.srcDir(if (standaloneSources) "shared/src/test/java" else "../armature-client-protocol/src/test/java")
}
val originalTestRoots = sourceSets.test.get().java.srcDirs.toList()
val generatedTests = layout.buildDirectory.dir("generated/test/java")
val prepareMinecraftTests = tasks.register("prepareMinecraftTests") {
    inputs.files(originalTestRoots)
    inputs.dir("src/versioned")
    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(generatedTests)
    doLast {
        val sources = linkedMapOf<String, File>()
        for (root in originalTestRoots) for (source in fileTree(root).matching { include("**/*.java") })
            sources[source.relativeTo(root).invariantSeparatorsPath] = source
        for (overlay in selectedOverlays) {
            val root = file("src/versioned/$overlay/test")
            for (source in fileTree(root).matching { include("**/*.java") })
                sources[source.relativeTo(root).invariantSeparatorsPath] = source
        }
        val output = generatedTests.get().asFile
        require(output.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        delete(output)
        for ((relative, source) in sources) {
            val target = output.resolve(relative)
            target.parentFile.mkdirs()
            target.writeText(if (modern) source.readText().replace("ResourceLocation", "Identifier") else source.readText())
        }
    }
}
sourceSets.test.get().java.setSrcDirs(listOf(generatedTests))
tasks.compileTestJava { dependsOn(prepareMinecraftTests) }
tasks.processResources {
    from(sharedResourceRoot) { include("armature/body-parts-v2.json") }
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", minecraftVersion)
    filesMatching("fabric.mod.json") { expand("version" to project.version, "minecraft" to minecraftVersion,
        "java" to if (unobfuscated) 25 else 21, "menu" to versionMatrix.getProperty("$minecraftVersion.menuRange")) }
    if (unobfuscated) filesMatching("armature-client.mixins.json") {
        filter { line -> line.replace("JAVA_21", "JAVA_25")
            .replace("\"GameRendererAccessor\",", "\"CameraFovAccessor\",") }
    }
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
                    ".gitignore", ".gitattributes", "gradle/minecraft-versions.properties", "gradle/wrapper/LICENSE")
            }
            into(destination)
        }
        // Gradle/Ant's default excludes omit these names even with explicit includes.
        for (name in listOf(".gitignore", ".gitattributes")) {
            file(name).copyTo(destination.resolve(name), overwrite = true)
        }
        copy {
            for (root in originalSourceRoots) from(root) {
                include(mainSources.java.includes)
                exclude { it.file.toPath().toAbsolutePath().normalize().startsWith(ownSources) }
                filter { line: String -> line.trimEnd('\r') }
            }
            into(destination.resolve("shared/src/main/java"))
        }
        copy {
            for (root in originalTestRoots) from(root) {
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
extensions.configure<FabricApiExtension> {
    configureTests {
        createSourceSet = true
        modId = "armature_client_test"
        enableGameTests = false
        enableClientGameTests = true
        eula = false
    }
}
val gametestSources = sourceSets.named("gametest").get()
val gametestRoots = gametestSources.java.srcDirs.toList()
val generatedGametests = layout.buildDirectory.dir("generated/gametest/java")
val prepareMinecraftGametests = tasks.register("prepareMinecraftGametests") {
    inputs.files(gametestRoots)
    inputs.property("minecraftVersion", minecraftVersion)
    outputs.dir(generatedGametests)
    doLast {
        val output = generatedGametests.get().asFile
        require(output.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        delete(output)
        for (root in gametestRoots) for (source in fileTree(root).matching { include("**/*.java") }) {
            var text = source.readText()
            if (modern) text = text.replace("ResourceLocation", "Identifier")
            if (unobfuscated && !next) text = text.replace("world.getClientWorld()", "world.getClientLevel()")
            if (next) text = text.replace("client.screen,", "client.gui.screen(),")
                .replace("world.getClientWorld()", "world.getConnection()")
            val target = output.resolve(source.relativeTo(root).invariantSeparatorsPath)
            target.parentFile.mkdirs()
            target.writeText(text)
        }
    }
}
gametestSources.java.setSrcDirs(listOf(generatedGametests))
tasks.named("compileGametestJava") { dependsOn(prepareMinecraftGametests) }
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
