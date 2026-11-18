package us.adfoc.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.bundling.AbstractArchiveTask

/**
 * `adfocus { ... }` in build.gradle.kts. Say where the file can be downloaded, one of three ways:
 *
 *     adfocus {
 *         url = "https://cdn.example.com/your-mod-1.4.0.jar"          // an address you know
 *         githubRelease("you/your-mod", "v1.4.0")                     // your jar on a GitHub release
 *         urlFile = layout.buildDirectory.file("publish/url.txt")     // an address a publish step wrote
 *         shortenAfter("publishGithub", "modrinth")                   // run after these tasks, when they run
 *     }
 *
 * The key: ADFOCUS_API_KEY, or adfocus.apiKey in your user gradle.properties (~/.gradle/gradle.properties), never in
 * the build file you publish.
 */
abstract class AdfocusExtension {
    /** The address to make a link for. */
    abstract val url: Property<String>

    /** A file holding the address (its first line), written by a publish step; used when url is not set. */
    abstract val urlFile: RegularFileProperty

    /** Your AdFoc.us key; ADFOCUS_API_KEY or the adfocus.apiKey property unless set. */
    abstract val apiKey: Property<String>

    /** https://adfoc.us unless ADFOCUS_BASE_URL or this says otherwise. */
    abstract val baseUrl: Property<String>

    /** The jar whose name githubRelease uses: remapJar (Fabric, Quilt) when there is one, else jar. */
    abstract val jarName: Property<String>

    /** Tasks the link is made after, whenever they run. */
    abstract val after: ListProperty<String>

    /** The address of your jar on a GitHub release: https://github.com/<repo>/releases/download/<tag>/<jar name>. */
    fun githubRelease(repo: String, tag: String) {
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) { "githubRelease wants the repository as owner/name, such as you/your-mod." }
        url.set(jarName.map { "https://github.com/$repo/releases/download/${tag.encodePath()}/${it.encodePath()}" })
    }

    /** The same for a file name you give. */
    fun githubRelease(repo: String, tag: String, fileName: String) {
        require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) { "githubRelease wants the repository as owner/name, such as you/your-mod." }
        url.set("https://github.com/$repo/releases/download/${tag.encodePath()}/${fileName.encodePath()}")
    }

    /** Make the link after these tasks whenever they run: modrinth, curseforge, publishMods, publishGithub, ... */
    fun shortenAfter(vararg taskNames: String) {
        after.addAll(*taskNames)
    }

    private fun String.encodePath() = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

/** Makes the AdFoc.us link: build/adfocus/short-link.txt holds the short address, short-link.json the whole answer. */
@UntrackedTask(because = "It asks AdFoc.us each time: the same address always gives back the same link")
abstract class AdfocusShortenTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val url: Property<String>

    @get:Internal
    abstract val urlFile: RegularFileProperty

    // never an input: inputs are written to the build cache and build scans
    @get:Internal
    abstract val apiKey: Property<String>

    @get:Input
    abstract val baseUrl: Property<String>

    @get:Internal
    abstract val gate: Property<String>

    @get:OutputFile
    abstract val shortUrlFile: RegularFileProperty

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    init {
        group = "publishing"
        description = "Makes an AdFoc.us short link for the file's download address."
    }

    @TaskAction
    fun shorten() {
        val address = url.orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: urlFile.orNull?.asFile?.takeIf { it.exists() }?.readLines()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: throw GradleException("AdFoc.us: no address to make a link for. Set adfocus { url = \"https://...\" }, githubRelease(\"you/your-mod\", \"v1.0.0\") or urlFile.")
        if (!Regex("^https?://\\S+$").matches(address)) throw GradleException("AdFoc.us: \"${address.take(100)}\" is not a web address.")
        val key = apiKey.orNull.orEmpty()
        val made = try {
            AdfocusClient(key, baseUrl.get(), gate.orNull).shorten(address)
        } catch (e: AdfocusException) {
            // its words never hold the key; said without the exception, so no trace can either
            throw GradleException("AdFoc.us: ${e.message}")
        }
        shortUrlFile.get().asFile.apply { parentFile.mkdirs() }.writeText("${made.shortUrl}\n")
        resultFile.get().asFile.writeText(Json.write(mapOf("url" to address, "short_url" to made.shortUrl, "link_id" to made.id, "existed" to made.existed)) + "\n")
        logger.lifecycle("AdFoc.us link for $address: ${made.shortUrl}${if (made.existed) " (you had this link already)" else ""}")
    }
}

class AdfocusPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("adfocus", AdfocusExtension::class.java)
        val p = project.providers
        ext.apiKey.convention(p.environmentVariable("ADFOCUS_API_KEY").orElse(p.gradleProperty("adfocus.apiKey")))
        ext.baseUrl.convention(p.environmentVariable("ADFOCUS_BASE_URL").orElse(AdfocusClient.DEFAULT_BASE_URL))
        // the jar a mod build ships: Loom's remapJar when there is one, else jar
        ext.jarName.convention(
            project.provider {
                val t = project.tasks.findByName("remapJar") ?: project.tasks.findByName("jar")
                (t as? AbstractArchiveTask)?.archiveFileName?.get() ?: "${project.name}-${project.version}.jar"
            },
        )
        val shorten = project.tasks.register("adfocusShorten", AdfocusShortenTask::class.java) {
            url.set(ext.url)
            urlFile.set(ext.urlFile)
            apiKey.set(ext.apiKey)
            baseUrl.set(ext.baseUrl)
            gate.set(p.environmentVariable("ADFOCUS_GATE"))
            shortUrlFile.set(project.layout.buildDirectory.file("adfocus/short-link.txt"))
            resultFile.set(project.layout.buildDirectory.file("adfocus/short-link.json"))
        }
        project.afterEvaluate {
            for (name in ext.after.get()) {
                val task = project.tasks.findByName(name)
                if (task == null) {
                    project.logger.warn("AdFoc.us: shortenAfter(\"$name\"): this build has no task named $name.")
                    continue
                }
                task.finalizedBy(shorten)
                shorten.configure { mustRunAfter(task) }
            }
        }
    }
}
