package us.adfoc.gradle

import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections

/** The plugin in real builds (Gradle TestKit) against a stand-in AdFoc.us on this computer: nothing reaches adfoc.us. */
class AdfocusPluginTest {
    companion object {
        val KEY = "afk_" + "G".repeat(43)

        class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

        val seen: MutableList<Seen> = Collections.synchronizedList(ArrayList())
        val script: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val made = LinkedHashMap<String, String>()
        lateinit var server: HttpServer
        lateinit var base: String

        @BeforeClass
        @JvmStatic
        fun start() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { ex ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                seen.add(Seen(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }, body))
                fun send(status: Int, text: String, retryAfter: String? = null) {
                    ex.responseHeaders.add("Content-Type", "application/json")
                    if (retryAfter != null) ex.responseHeaders.add("Retry-After", retryAfter)
                    val b = text.toByteArray()
                    ex.sendResponseHeaders(status, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
                val next = synchronized(script) { if (script.isEmpty()) null else script.removeAt(0) }
                when {
                    next != null -> send(next, """{"error":{"code":"${if (next == 429) "rate_limited" else "server_error"}","message":"Scripted."}}""", if (next == 429) "1" else null)
                    ex.requestURI.path != "/api/v2/links" || ex.requestMethod != "POST" -> send(404, """{"error":{"code":"not_found","message":"There is no such request."}}""")
                    ex.requestHeaders.getFirst("X-API-Key") != KEY -> send(401, """{"error":{"code":"bad_key","message":"No AdFoc.us account has this key."}}""")
                    else -> {
                        val url = (Json.parse(body) as Map<*, *>)["url"] as String
                        if (url.contains("linkvertise")) {
                            send(400, """{"error":{"code":"bad_url","message":"That address is on an ad link site."}}""")
                        } else {
                            val had = synchronized(made) { made[url] }
                            val code = had ?: synchronized(made) { "x${3000 + made.size}".also { made[url] = it } }
                            send(if (had != null) 200 else 201, Json.write(mapOf("link" to mapOf("id" to code.drop(1).toLong(), "code" to code, "short_url" to "https://adfoc.us/$code", "destination" to url), "existed" to (had != null))))
                        }
                    }
                }
            }
            server.start()
            base = "http://127.0.0.1:${server.address.port}"
        }

        @AfterClass
        @JvmStatic
        fun stop() = server.stop(0)
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("adfocus-gradle").toFile()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"your-mod\"\n")
        seen.clear()
        script.clear()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun build(script: String, vararg args: String, key: String? = KEY, fail: Boolean = false): BuildResult {
        File(dir, "build.gradle.kts").writeText(script)
        val env = HashMap(System.getenv())
        env.remove("ADFOCUS_API_KEY")
        if (key != null) env["ADFOCUS_API_KEY"] = key
        env["ADFOCUS_BASE_URL"] = base
        val runner = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withEnvironment(env)
            .withArguments(*args, "--stacktrace")
        System.getProperty("adfocus.testkit.dir")?.let { runner.withTestKitDir(File(it)) }
        return if (fail) runner.buildAndFail() else runner.build()
    }

    private val head = "plugins {\n    java\n    id(\"us.adfoc.shorten\")\n}\nversion = \"1.4.0\"\n"

    @Test
    fun `makes a link for the address given and writes it down`() {
        val r = build("$head\nadfocus {\n    url = \"https://cdn.example.com/your-mod-1.4.0.jar\"\n}\n", "adfocusShorten")
        assertEquals(TaskOutcome.SUCCESS, r.task(":adfocusShorten")?.outcome)
        assertTrue(r.output, r.output.contains("AdFoc.us link for https://cdn.example.com/your-mod-1.4.0.jar: https://adfoc.us/x"))
        val short = File(dir, "build/adfocus/short-link.txt").readText().trim()
        assertTrue(short, short.matches(Regex("^https://adfoc\\.us/x\\d+$")))
        val json = Json.parse(File(dir, "build/adfocus/short-link.json").readText()) as Map<*, *>
        assertEquals(short, json["short_url"])
        val req = seen.single()
        assertEquals(KEY, req.headers["x-api-key"])
        assertEquals("adfocus-gradle/${AdfocusClient.VERSION}", req.headers["user-agent"])
        assertFalse("the key is never in the build's output", r.output.contains(KEY))
    }

    @Test
    fun `builds the jar's address on a GitHub release`() {
        build("$head\nadfocus {\n    githubRelease(\"you/your-mod\", \"v\${project.version}\")\n}\n", "adfocusShorten")
        val body = Json.parse(seen.single().body) as Map<*, *>
        assertEquals("https://github.com/you/your-mod/releases/download/v1.4.0/your-mod-1.4.0.jar", body["url"])
    }

    @Test
    fun `works with the configuration cache`() {
        val script = "$head\nadfocus {\n    githubRelease(\"you/your-mod\", \"v1.4.0\")\n}\n"
        build(script, "adfocusShorten", "--configuration-cache")
        val again = build(script, "adfocusShorten", "--configuration-cache")
        assertTrue(again.output, again.output.contains("Reusing configuration cache"))
        assertEquals(2, seen.size)
        assertTrue(again.output.contains("you had this link already"))
    }

    @Test
    fun `reads the address a publish step wrote`() {
        val script = "$head\nval publishFake = tasks.register(\"publishFake\") {\n    val out = layout.buildDirectory.file(\"publish/url.txt\")\n    outputs.file(out)\n    doLast { out.get().asFile.writeText(\"https://cdn.modrinth.com/data/AbCd/versions/EfGh/your-mod-1.4.0.jar\\n\") }\n}\nadfocus {\n    urlFile = layout.buildDirectory.file(\"publish/url.txt\")\n    shortenAfter(\"publishFake\")\n}\n"
        val r = build(script, "publishFake")
        assertEquals(TaskOutcome.SUCCESS, r.task(":publishFake")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, r.task(":adfocusShorten")?.outcome)
        assertEquals("https://cdn.modrinth.com/data/AbCd/versions/EfGh/your-mod-1.4.0.jar", (Json.parse(seen.single().body) as Map<*, *>)["url"])
    }

    @Test
    fun `stops with plain words when there is no key or the key is refused`() {
        val r = build("$head\nadfocus { url = \"https://cdn.example.com/a.jar\" }\n", "adfocusShorten", key = null, fail = true)
        assertTrue(r.output, r.output.contains("No AdFoc.us key: set ADFOCUS_API_KEY"))
        assertTrue(seen.isEmpty())
        val wrong = "afk_" + "W".repeat(43)
        val r2 = build("$head\nadfocus { url = \"https://cdn.example.com/a.jar\" }\n", "adfocusShorten", key = wrong, fail = true)
        assertTrue(r2.output, r2.output.contains("AdFoc.us: No AdFoc.us account has this key."))
        assertFalse(r2.output.contains(wrong))
    }

    @Test
    fun `says why AdFocus refused an address`() {
        val r = build("$head\nadfocus { url = \"https://linkvertise.com/123\" }\n", "adfocusShorten", fail = true)
        assertTrue(r.output, r.output.contains("AdFoc.us: That address is on an ad link site."))
    }

    @Test
    fun `waits and tries again when AdFocus is busy`() {
        script.addAll(listOf(429, 503))
        val r = build("$head\nadfocus { url = \"https://cdn.example.com/busy.jar\" }\n", "adfocusShorten")
        assertEquals(TaskOutcome.SUCCESS, r.task(":adfocusShorten")?.outcome)
        assertEquals(3, seen.size)
    }

    @Test
    fun `warns about a task name that does not exist, and never sends a key in the open`() {
        val r = build("$head\nadfocus {\n    url = \"https://cdn.example.com/a.jar\"\n    baseUrl = \"http://adfoc.us\"\n    shortenAfter(\"modrinthh\")\n}\n", "adfocusShorten", fail = true)
        assertTrue(r.output, r.output.contains("this build has no task named modrinthh"))
        assertTrue(r.output, r.output.contains("must start with https://"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `reads Retry-After and the site's address the same way as the other kits`() {
        assertEquals(3L, AdfocusClient.retryAfterOf("3"))
        assertNull(AdfocusClient.retryAfterOf("soon"))
        assertNotNull(AdfocusClient.retryAfterOf("Wed, 21 Oct 2099 07:28:00 GMT"))
        assertEquals("https://links.example.com", AdfocusClient.siteAddress("https://links.example.com/api/v2/"))
        assertEquals("""{"url":"a\"b\\c\n"}""", Json.write(mapOf("url" to "a\"b\\c\n")))
        assertEquals(mapOf("a" to listOf(1L, true, null, "é")), Json.parse("""{"a":[1,true,null,"é"]}"""))
    }
}
