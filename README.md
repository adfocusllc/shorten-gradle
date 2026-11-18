# AdFoc.us Gradle plugin

AdFoc.us is a link shortener that pays you for the visitors your download links bring. This Gradle plugin makes an
AdFoc.us short link for your mod's download address when you publish it: to GitHub, Modrinth, CurseForge or anywhere
else. Visitors who open the link see one ad for about 9 seconds, then get your file.

Plugin id `us.adfoc.shorten`. Gradle 8 or newer, Java 11 or newer (Minecraft builds run on 17 or 21).

## Set up

1. Get an API key. On the [Developers page](https://adfoc.us/developers), press **Make an API key**, or open
   **Tools**, then **API keys**, in your AdFoc.us Dashboard. Pick **Create links only**: it can make links and read
   nothing else. The key is shown once, so copy it then.
2. Keep it out of your build file. Either set `ADFOCUS_API_KEY` (in CI, from a secret), or add it to your own
   `~/.gradle/gradle.properties` (never the project's):

   ```properties
   adfocus.apiKey=afk_example000000000000000000000000000000000000
   ```

   In GitHub Actions, put the key in a repository secret and hand it to the step that runs Gradle:

   ```yaml
   - run: ./gradlew publishGithub
     env:
       ADFOCUS_API_KEY: ${{ secrets.ADFOCUS_API_KEY }}
   ```

3. In `build.gradle.kts`:

   ```kotlin
   plugins {
       id("us.adfoc.shorten") version "0.1.0"
   }

   adfocus {
       // your jar's address on a GitHub release (remapJar's name with Fabric or Quilt, else jar's)
       githubRelease("you/your-mod", "v${project.version}")
       // make the link after these tasks, whenever they run
       shortenAfter("publishGithub")
   }
   ```

Now `./gradlew publishGithub` publishes as before and then prints:

```
AdFoc.us link for https://github.com/you/your-mod/releases/download/v1.4.0/your-mod-1.4.0.jar: https://adfoc.us/x1234567
```

`./gradlew adfocusShorten` makes it on its own. The same address always gives back the same link, so running it again
never makes a second one.

## Where the address comes from

Pick one:

```kotlin
adfocus {
    url = "https://cdn.example.com/your-mod-1.4.0.jar"            // an address you know
    githubRelease("you/your-mod", "v1.4.0")                       // your jar on a GitHub release
    githubRelease("you/your-mod", "v1.4.0", "your-mod-all.jar")   // another file on it
    urlFile = layout.buildDirectory.file("publish/url.txt")       // the first line of a file a publish step wrote
}
```

Modrinth and CurseForge give a file its address only once it is uploaded. Have your publish step write that address
to a file, point `urlFile` at it, and name the publish task in `shortenAfter`, for example `shortenAfter("modrinth")`
or `shortenAfter("publishMods")`. A name with no such task gets a warning, not a silent skip.

## What it leaves behind

- `build/adfocus/short-link.txt`: the short address, for the next step of your build or your CI.
- `build/adfocus/short-link.json`: `{"url", "short_url", "link_id", "existed"}`.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `url`, `githubRelease(...)`, `urlFile` | | Where the file can be downloaded (one of them) |
| `shortenAfter(...)` | | Tasks the link is made after |
| `apiKey` | `ADFOCUS_API_KEY`, then the `adfocus.apiKey` property | Your key |
| `baseUrl` | `ADFOCUS_BASE_URL`, then `https://adfoc.us` | The AdFoc.us address |
| `jarName` | `remapJar`'s file name, else `jar`'s | The file name `githubRelease("owner/name", "tag")` puts at the end of the address |

When AdFoc.us is busy or has a problem, the task waits and tries again (up to 3 times) before it fails. An address
AdFoc.us won't accept (another ad link site, for one) fails the task with AdFoc.us's reason.

The key is sent only to AdFoc.us, in the `X-API-Key` header over https; it is never printed, and never a task input,
so it stays out of the build cache and build scans. The plugin collects nothing about you. It works with Gradle's
configuration cache.

## Build it yourself

`./gradlew build` builds the plugin and runs its tests. The tests run real Gradle builds against a stand-in AdFoc.us
on your own computer, so they need no key and send nothing to adfoc.us.

## License

MIT, copyright AdFoc.us LLC.
