plugins {
    id("java")
}

// Identity comes from gradle.properties, so the Gradle project and the generated
// manifest.json stay in sync. The literals are only fallbacks for a missing property.
group = providers.gradleProperty("group").getOrElse("org.example")
version = providers.gradleProperty("version").getOrElse("1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

// --- Local Hytale server dependency -------------------------------------------------
// Resolved path: <hytale.installDir>/<hytale.patchline>/package/game/latest/Server/HytaleServer.jar
//
// Precedence for the install root:
//   1. hytale.installDir  (-P CLI flag > this project's gradle.properties > ~/.gradle/gradle.properties)
//   2. hytale_home        (community convention: the folder that contains "install" and "UserData")
//   3. %APPDATA%/Hytale/install
val hytaleInstallDir = providers.gradleProperty("hytale.installDir")
    .orElse(providers.gradleProperty("hytale_home").map { "$it/install" })
    .orElse(providers.environmentVariable("APPDATA").map { "$it/Hytale/install" })
    .map { it.trim().trimEnd('/', '\\') }
    .getOrElse("")

val hytalePatchline = providers.gradleProperty("hytale.patchline")
    .getOrElse("release")
    .trim()

val hytaleServerJar: File = file(
    "$hytaleInstallDir/$hytalePatchline/package/game/latest/Server/HytaleServer.jar"
)

val verifyHytaleDependency = tasks.register("verifyHytaleDependency") {
    group = "verification"
    description = "Fails with an actionable message when the configured Hytale server jar is missing."
    val jar = hytaleServerJar
    val installDir = hytaleInstallDir
    val patchline = hytalePatchline
    doLast {
        if (patchline != "release" && patchline != "pre-release") {
            throw GradleException(
                "Invalid 'hytale.patchline' = '$patchline'. Expected 'release' or 'pre-release'."
            )
        }
        if (!jar.isFile) {
            throw GradleException(
                """
                Hytale server jar not found:
                  ${jar.absolutePath}
                From:
                  hytale.installDir = ${installDir.ifEmpty { "<unset>" }}
                  hytale.patchline   = $patchline
                Point 'hytale.installDir' at the folder that contains the '$patchline'
                folder, in gradle.properties, ~/.gradle/gradle.properties, or -P.
                """.trimIndent()
            )
        }
        logger.lifecycle("Hytale server jar: ${jar.absolutePath}")
    }
}

dependencies {
    // compileOnly: the server jar is supplied by the Hytale runtime and must never be
    // bundled into the plugin jar, so no `implementation` and no main `runtimeOnly`.
    compileOnly(files(hytaleServerJar))
    // testRuntimeClasspath does not inherit compileOnly, so tests need it explicitly.
    testCompileOnly(files(hytaleServerJar))
    testRuntimeOnly(files(hytaleServerJar))

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Without this, a missing jar would silently produce an empty classpath and surface later
// as a confusing "package does not exist" compile error.
tasks.withType<JavaCompile>().configureEach {
    dependsOn(verifyHytaleDependency)
}

// --- Run the server with a debugger attached ----------------------------------------
// Server state (worlds, configs, logs) is kept inside the project instead of the install.
val hytaleRunDir: File = layout.projectDirectory.dir("run").asFile

// The server takes its assets via --assets. In this install they are the Assets.zip that
// sits next to the Server/ folder holding the jar.
val hytaleAssetsPath: String = providers.gradleProperty("hytale.assets")
    .getOrElse(File(hytaleServerJar.parentFile.parentFile, "Assets.zip").absolutePath)

val hytaleDebugPort: String = providers.gradleProperty("hytale.debug.port").getOrElse("5005")
val hytaleDebugSuspend: Boolean = providers.gradleProperty("hytale.debug.suspend")
    .getOrElse("false")
    .trim()
    .equals("true", ignoreCase = true)

val hytaleExtraServerArgs: List<String> = providers.gradleProperty("hytale.serverArgs")
    .getOrElse("")
    .trim()
    .let { if (it.isEmpty()) emptyList() else it.split(Regex("\\s+")) }

// The game parses manifest.json's Version as a semver, so a Maven-style project version such
// as 1.0-SNAPSHOT is padded to 1.0.0-SNAPSHOT. Throws when it cannot be normalised.
fun normaliseSemver(raw: String): String {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    fun fail(): Nothing = throw IllegalArgumentException(
        "Invalid 'version' = '$raw': manifest.json needs a semver such as 1.0.0 or 1.0.0-SNAPSHOT. " +
            "Set 'version' in gradle.properties or use -Pversion=<semver>."
    )
    if (trimmed.isEmpty()) fail()
    val plus = trimmed.indexOf('+')
    val core = if (plus >= 0) trimmed.substring(0, plus) else trimmed
    val build = if (plus >= 0) trimmed.substring(plus + 1) else ""
    val dash = core.indexOf('-')
    val numbers = if (dash >= 0) core.substring(0, dash) else core
    val preRelease = if (dash >= 0) core.substring(dash + 1) else ""
    val parts = numbers.split('.')
    if (parts.size > 3) fail()
    if (parts.any { part -> part.isEmpty() || !part.all { it.isDigit() } }) fail()
    val padded = (parts + List(3 - parts.size) { "0" }).joinToString(".")
    return buildString {
        append(padded)
        if (preRelease.isNotEmpty()) append('-').append(preRelease)
        if (build.isNotEmpty()) append('+').append(build)
    }
}

fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

// Comma separated property values, ignoring empty entries.
fun propertyEntries(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

// "Hytale:NPC=*, Other:Plugin=>=1.0.0" -> a JSON object; a missing range means "*".
fun renderDependencyMap(property: String, raw: String): String {
    val entries = propertyEntries(raw)
    if (entries.isEmpty()) return "{}"
    val rendered = entries.map { entry ->
        val sep = entry.indexOf('=')
        val key = (if (sep >= 0) entry.substring(0, sep) else entry).trim()
        val range = (if (sep >= 0) entry.substring(sep + 1) else "").trim().ifEmpty { "*" }
        if (!key.contains(':')) {
            throw IllegalArgumentException(
                "'$property' entry '$entry' must look like Group:Name=<version range>."
            )
        }
        "    \"${jsonEscape(key)}\": \"${jsonEscape(range)}\""
    }
    return "{\n${rendered.joinToString(",\n")}\n  }"
}

// "Alice|alice@example.com|https://example.com, Bob" -> a JSON array of author objects.
fun renderAuthors(raw: String): String {
    val entries = propertyEntries(raw)
    if (entries.isEmpty()) return "[]"
    val rendered = entries.map { entry ->
        val parts = entry.split('|').map { it.trim() }
        val name = parts.getOrNull(0).orEmpty()
        if (name.isEmpty()) {
            throw IllegalArgumentException(
                "'hytale.plugin.authors' entry '$entry' must look like Name|Email|Url."
            )
        }
        val fields = mutableListOf("      \"Name\": \"${jsonEscape(name)}\"")
        parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { fields.add("      \"Email\": \"${jsonEscape(it)}\"") }
        parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { fields.add("      \"Url\": \"${jsonEscape(it)}\"") }
        "    {\n${fields.joinToString(",\n")}\n    }"
    }
    return "[\n${rendered.joinToString(",\n")}\n  ]"
}

// "Spawning=org.example.SpawningPlugin" -> a JSON array of sub-plugin manifests.
fun renderSubPlugins(raw: String): String {
    val entries = propertyEntries(raw)
    if (entries.isEmpty()) return "[]"
    val rendered = entries.map { entry ->
        val sep = entry.indexOf('=')
        val name = if (sep > 0) entry.substring(0, sep).trim() else ""
        val main = if (sep > 0) entry.substring(sep + 1).trim() else ""
        if (name.isEmpty() || main.isEmpty()) {
            throw IllegalArgumentException(
                "'hytale.plugin.subPlugins' entry '$entry' must look like Name=MainClass."
            )
        }
        "    {\n      \"Name\": \"${jsonEscape(name)}\",\n      \"Main\": \"${jsonEscape(main)}\"\n    }"
    }
    return "[\n${rendered.joinToString(",\n")}\n  ]"
}

// Emits the manifest in the same key order the game's Asset Editor writes. The *Json arguments
// are already-rendered JSON fragments.
fun renderPluginManifest(
    group: String,
    name: String,
    version: String,
    description: String,
    authorsJson: String,
    website: String,
    mainClass: String,
    serverVersion: String,
    dependenciesJson: String,
    optionalDependenciesJson: String,
    loadBeforeJson: String,
    subPluginsJson: String,
    disabledByDefault: Boolean,
    includesAssetPack: Boolean,
): String {
    val lines = mutableListOf(
        "{",
        "  \"Group\": \"${jsonEscape(group)}\",",
        "  \"Name\": \"${jsonEscape(name)}\",",
        "  \"Version\": \"${jsonEscape(version)}\",",
        "  \"Description\": \"${jsonEscape(description)}\",",
        "  \"Authors\": $authorsJson,",
        "  \"Website\": \"${jsonEscape(website)}\",",
    )
    if (mainClass.isNotEmpty()) {
        lines.add("  \"Main\": \"${jsonEscape(mainClass)}\",")
    }
    lines.add("  \"ServerVersion\": \"${jsonEscape(serverVersion)}\",")
    lines.add("  \"Dependencies\": $dependenciesJson,")
    lines.add("  \"OptionalDependencies\": $optionalDependenciesJson,")
    lines.add("  \"LoadBefore\": $loadBeforeJson,")
    lines.add("  \"DisabledByDefault\": $disabledByDefault,")
    lines.add("  \"IncludesAssetPack\": $includesAssetPack,")
    lines.add("  \"SubPlugins\": $subPluginsJson")
    lines.add("}")
    return lines.joinToString("\n") + "\n"
}

// --- Plugin manifest scaffold --------------------------------------------------------
// debugServer rewrites run/mods/<group>.<name>/manifest.json from the properties below on
// every run, using the same directory convention as the in-game Asset Editor. The file is a
// build output: configure the manifest here, never by editing the generated JSON.
val hytalePluginDescription: String =
    providers.gradleProperty("hytale.plugin.description").getOrElse("").trim()
val hytalePluginAuthors: String =
    providers.gradleProperty("hytale.plugin.authors").getOrElse("").trim()
val hytalePluginWebsite: String =
    providers.gradleProperty("hytale.plugin.website").getOrElse("").trim()
val hytalePluginMainClass: String =
    providers.gradleProperty("hytale.plugin.mainClass").getOrElse("").trim()
val hytalePluginServerVersion: String =
    providers.gradleProperty("hytale.plugin.serverVersion").getOrElse("*").trim()
val hytalePluginDependencies: String =
    providers.gradleProperty("hytale.plugin.dependencies").getOrElse("").trim()
val hytalePluginOptionalDependencies: String =
    providers.gradleProperty("hytale.plugin.optionalDependencies").getOrElse("").trim()
val hytalePluginLoadBefore: String =
    providers.gradleProperty("hytale.plugin.loadBefore").getOrElse("").trim()
val hytalePluginSubPlugins: String =
    providers.gradleProperty("hytale.plugin.subPlugins").getOrElse("").trim()
val hytalePluginDisabledByDefault: Boolean = providers.gradleProperty("hytale.plugin.disabledByDefault")
    .getOrElse("false")
    .trim()
    .equals("true", ignoreCase = true)
val hytalePluginIncludesAssetPack: Boolean = providers.gradleProperty("hytale.plugin.includesAssetPack")
    .getOrElse("false")
    .trim()
    .equals("true", ignoreCase = true)

// Resolved while configuring: a task action must not read project or script members, or the
// configuration cache cannot serialize it. Rendering problems are reported by the action.
val hytaleManifestGroup: String = project.group.toString()
val hytaleManifestName: String = project.name
val hytaleRawVersion: String = project.version.toString()
val hytaleDeclaredName: String = providers.gradleProperty("name").getOrElse("").trim()
val hytaleNameMismatch: String =
    if (hytaleDeclaredName.isNotEmpty() && hytaleDeclaredName != hytaleManifestName) {
        "gradle.properties sets name='$hytaleDeclaredName' but the Gradle project is " +
            "'$hytaleManifestName', so settings.gradle.kts did not pick up the property."
    } else {
        ""
    }

val hytaleModDir: File = File(hytaleRunDir, "mods/$hytaleManifestGroup.$hytaleManifestName")
val hytaleManifestFile: File = File(hytaleModDir, "manifest.json")
val hytaleManifestResult = runCatching {
    renderPluginManifest(
        group = hytaleManifestGroup,
        name = hytaleManifestName,
        version = normaliseSemver(hytaleRawVersion),
        description = hytalePluginDescription,
        authorsJson = renderAuthors(hytalePluginAuthors),
        website = hytalePluginWebsite,
        mainClass = hytalePluginMainClass,
        serverVersion = hytalePluginServerVersion,
        dependenciesJson = renderDependencyMap("hytale.plugin.dependencies", hytalePluginDependencies),
        optionalDependenciesJson =
            renderDependencyMap("hytale.plugin.optionalDependencies", hytalePluginOptionalDependencies),
        loadBeforeJson = renderDependencyMap("hytale.plugin.loadBefore", hytalePluginLoadBefore),
        subPluginsJson = renderSubPlugins(hytalePluginSubPlugins),
        disabledByDefault = hytalePluginDisabledByDefault,
        includesAssetPack = hytalePluginIncludesAssetPack,
    )
}
val hytaleManifestText: String = hytaleManifestResult.getOrElse { "" }
val hytaleManifestError: String = hytaleManifestResult.exceptionOrNull()?.message.orEmpty()

val generatePluginManifest = tasks.register("generatePluginManifest") {
    group = "hytale"
    description = "Writes run/mods/<group>.<name>/manifest.json from the hytale.plugin.* properties."

    val dir = hytaleModDir
    val manifest = hytaleManifestFile
    val text = hytaleManifestText
    val error = hytaleManifestError
    val nameMismatch = hytaleNameMismatch
    val folderName = "$hytaleManifestGroup.$hytaleManifestName"

    mapOf(
        "folder" to folderName,
        "version" to hytaleRawVersion,
        "description" to hytalePluginDescription,
        "authors" to hytalePluginAuthors,
        "website" to hytalePluginWebsite,
        "mainClass" to hytalePluginMainClass,
        "serverVersion" to hytalePluginServerVersion,
        "dependencies" to hytalePluginDependencies,
        "optionalDependencies" to hytalePluginOptionalDependencies,
        "loadBefore" to hytalePluginLoadBefore,
        "subPlugins" to hytalePluginSubPlugins,
        "disabledByDefault" to hytalePluginDisabledByDefault,
        "includesAssetPack" to hytalePluginIncludesAssetPack,
    ).forEach { (key, value) -> inputs.property(key, value) }
    outputs.file(manifest)

    doLast {
        if (nameMismatch.isNotEmpty()) {
            throw GradleException(nameMismatch)
        }
        if (error.isNotEmpty()) {
            throw GradleException(error)
        }
        val invalid = folderName.filterNot { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (invalid.isNotEmpty()) {
            throw GradleException(
                "Cannot use '$folderName' as the mod folder name: '$invalid' is not allowed. " +
                    "Change 'group' or 'name' in gradle.properties."
            )
        }
        dir.mkdirs()
        manifest.writeText(text, Charsets.UTF_8)
        logger.lifecycle("Wrote plugin manifest: ${manifest.absolutePath}")
    }
}

// --- Mirror the pack's assets back into the project -----------------------------------
// When debugServer stops, the pack's assets and its descriptor are mirrored out of
// run/mods/<group>.<name> into src/main/resources, so in-game authored assets end up in the
// plugin jar in the layout the server loads (manifest.json, Common/... and Server/... at the
// jar root — the same top-level layout as the game's own Assets.zip). Everything else in the
// mod folder, notably the staged bytecode, stays in run/. That makes src/main/resources a
// generated directory: files written there by hand are removed on the next stop. Run it by hand
// with `gradlew syncAssets`.
val hytaleResourcesDir: File = layout.projectDirectory.dir("src/main/resources").asFile

val syncAssets = tasks.register<Sync>("syncAssets") {
    group = "hytale"
    description = "Mirrors the pack's assets (Common/, Server/) and manifest.json into src/main/resources."

    // Copied into locals: an execution-time lambda must not read script members, or the
    // configuration cache cannot serialize it.
    val source = hytaleModDir
    val target = hytaleResourcesDir

    // Without this guard a missing mod folder counts as an empty source and Sync would delete
    // every file in src/main/resources.
    onlyIf { source.isDirectory }
    from(source) {
        // Only the pack's assets and its descriptor travel; bytecode stays a run/ artefact.
        include("manifest.json", "Common/**", "Server/**")
    }
    into(target)
    doLast {
        val files = target.walkTopDown().count { it.isFile }
        logger.lifecycle("Mirrored $files file(s) into ${target.absolutePath}")
    }
}

// --- Stage the compiled plugin into the mod folder ------------------------------------
// The mods scan only ever discovers regular files, and its jar loader reads manifest.json from
// inside a zip, so loose bytecode in a mod folder would be ignored. The other route is the
// classpath: loadPluginsInClasspath calls ClassLoader.getResources("manifest.json"), so putting
// the plugin's folder on the debugServer classpath makes the server find that folder's
// manifest.json and load its classes. The bytecode is staged at the folder root, keeping the
// package directories (org/example/...), which is exactly what a classpath root looks like.
//
// Copy rather than Sync on purpose: the destination is shared with manifest.json, Server/... and
// whatever else the game writes into its own mod folder, so staging must never delete anything.
val copyPluginClasses = tasks.register<Copy>("copyPluginClasses") {
    group = "hytale"
    description = "Compiles the plugin and copies its bytecode into run/mods/<group>.<name>."

    // Copied into locals: an execution-time lambda must not read script members, or the
    // configuration cache cannot serialize it.
    val target = hytaleModDir
    val mainClass = hytalePluginMainClass

    dependsOn(tasks.named("classes"))
    from(sourceSets.getByName("main").output.classesDirs)
    into(target)
    doLast {
        val staged = target.walkTopDown().count { it.isFile && it.name.endsWith(".class") }
        logger.lifecycle("Staged $staged class file(s) in ${target.absolutePath}")
        if (mainClass.isEmpty()) {
            logger.warn(
                "hytale.plugin.mainClass is not set, so manifest.json has no \"Main\" and the " +
                    "server treats this folder as an asset pack: the staged classes will not load."
            )
        }
    }
}

// syncAssets reads the mod folder that generatePluginManifest and copyPluginClasses write
// into, so the ordering has to be declared or Gradle rejects the build when they run together.
syncAssets.configure { mustRunAfter(copyPluginClasses, generatePluginManifest) }

val debugHytaleServer = tasks.register<JavaExec>("debugServer") {
    group = "hytale"
    description = "Runs the Hytale server from the configured install with a JDWP debug listener."
    dependsOn(verifyHytaleDependency)
    dependsOn(generatePluginManifest)
    dependsOn(copyPluginClasses)
    finalizedBy(syncAssets)

    // The plugin's own folder joins the classpath: the server discovers manifest.json through
    // ClassLoader.getResources("manifest.json") and loads the staged classes from that same root.
    classpath = files(hytaleServerJar, hytaleModDir)
    mainClass.set("com.hypixel.hytale.Main")

    workingDir = hytaleRunDir

    // The jar's manifest declares Enable-Native-Access: ALL-UNNAMED, which the JVM only
    // honours for a `java -jar` launch, so it is passed explicitly here. The JDWP listener
    // lets a debugger attach to this JVM; with -Phytale.debug.suspend=true the server waits
    // for the debugger before running any code. Note that JDWP spells these y/n, not
    // true/false.
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=${if (hytaleDebugSuspend) "y" else "n"}," +
            "address=localhost:$hytaleDebugPort"
    )

    args("--assets", hytaleAssetsPath, "--allow-op")
    if (hytaleExtraServerArgs.isNotEmpty()) {
        args(*hytaleExtraServerArgs.toTypedArray())
    }

    // So the server's interactive console reads from the terminal.
    // (`in` is a Kotlin keyword, hence the backticks around System.`in`.)
    standardInput = System.`in`

    // Copied into locals: a task action that reads script-level values directly would
    // capture the build script object, which the configuration cache cannot serialize.
    val assets = hytaleAssetsPath
    val runDir = hytaleRunDir
    val debugPort = hytaleDebugPort
    val debugSuspend = hytaleDebugSuspend
    doFirst {
        runDir.mkdirs()
        if (!File(assets).exists()) {
            throw GradleException(
                """
                Hytale assets not found:
                  $assets
                They default to Assets.zip beside the Server/ folder of the configured
                install. Override with -Phytale.assets=<path>.
                """.trimIndent()
            )
        }
        logger.lifecycle("Debug listener: localhost:$debugPort (suspend=$debugSuspend)")
        logger.lifecycle("Server working directory: ${runDir.absolutePath}")
    }
}

tasks.test {
    useJUnitPlatform()
}