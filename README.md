# BlackSkija

### I totally didn't make readme with AI. Totally.

GPU-accelerated UI rendering for Minecraft (Fabric), powered by [Skija](https://github.com/HumbleUI/Skija)
(the JVM bindings to Google's [Skia](https://skia.org/)). BlackSkija draws your UI onto a
Skija `Canvas` and composites the result over Minecraft's frame — on **Vulkan** or OpenGL —
so you get anti-aliased shapes, gradients, blur, drop shadows, and rich text without fighting
`blaze3d`.

> **Client-side mod / library.** BlackSkija is meant to be used as a dependency by other
> mods that want a real 2D graphics API for their screens and overlays.

## Features

- **Anti-aliased 2D** — rects, rounded / half-rounded rects, circles, lines, multi-stop
  gradients, Gaussian drop shadows, clipping, transforms, per-call alpha.
- **Rich text** — Skija's paragraph engine: glyph fallback, measuring, word-wrap, gradient
  fill, and shadow. Bundled JetBrains Mono default; register your own fonts by family name.
- **Minecraft textures, resource-pack aware** — draw live MC textures, atlas sprites, and
  rendered `ItemStack`s by *borrowing* their GPU handle (no CPU copy). Reflects the active
  resource pack and item NBT/components.
- **Vulkan *and* OpenGL** — composites over `blaze3d` on either backend, with a 0-latency
  path on Vulkan; gracefully disables on a GPU API it can't adapt. Most Skija/NanoVG UI mods
  are GL-only.
- **Immediate-mode** — call from any render-thread hook, no begin/end; an idle frame costs nothing.

## Requirements

|                        |                           |
|------------------------|---------------------------|
| Minecraft              | `26.2`                    |
| Fabric Loader          | `>= 0.19.3`               |
| Fabric API             | required                  |
| Fabric Language Kotlin | `>= 1.13.12+kotlin.2.4.0` |
| Java                   | 25                        |

The Skija native library is **not** bundled in the jar — it is downloaded at runtime and
verified against an SHA-256 manifest generated at build time, so only the small `skija-shared`
classes ship inside the mod.

## Adding the dependency

Released via [JitPack](https://jitpack.io/#DreamcastERA1/BlackSkija):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.DreamcastERA1:BlackSkija:{version}")
}
```

The public API (`org.blackaddons.blackskija.api`) exposes Skija types directly, so
`skija-shared` comes in transitively on your compile classpath.

## Quick start

Subclass `SkijaScreen` and draw with the `Skija` API in gui-scaled coordinates:

```kotlin
import net.minecraft.network.chat.Component
import org.blackaddons.blackskija.api.Gradient
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.draw.SkijaText
import org.blackaddons.blackskija.api.screen.SkijaScreen
import java.awt.Color

class MyScreen : SkijaScreen(Component.literal("My Screen")) {
    override fun draw() {
        Skija.dropShadow(40, 40, 320, 200, 26, 2, 16, Color(0, 0, 0, 150))
        Skija.gradientRect(40, 40, 320, 200, Color(30, 33, 43), Color(18, 19, 24), Gradient.TOP_BOTTOM, 14)
        Skija.hollowRect(40, 40, 320, 200, 1.5, Color(90, 130, 255, 200), 14)
        SkijaText.drawGradient("Hello, Skija", 56, 56, Color(90, 130, 255), Color(150, 205, 255), 15f)
    }
}
```

Open it like any Minecraft screen (`Minecraft.getInstance().setScreenAndShow(MyScreen())`). The
world keeps rendering behind it; ESC closes it.

## HUD / drawing from anywhere

`Skija` is immediate-mode: call it from **any** render-thread hook (a HUD callback, several
independent modules, your own overlay) — no begin/end, no frame to manage. Each call is
queued and composited once per frame; an idle frame with no calls costs nothing.

```kotlin
// e.g., from a per-frame HUD hook — call as often as you like, from as many places as you like
Skija.rect(8, 8, 132, 22, Color(0, 0, 0, 160), 6)
SkijaText.draw("FPS: ${currentFps()}", 16, 14, Color.WHITE, 10f)
```

> All calls (drawing *and* measuring like `SkijaText.width`) are **render-thread only** — they
> touch native Skija objects shared with the frame flush.

There's no `enable` to flip and no single render slot to share: every source just draws, and
order follows call order within the frame. (`SkijaScreen`/`SkijaOverlay.content` is simply
a convenience for one managed source — screens compose on top of HUD.)

API surface lives under `org.blackaddons.blackskija.api`:

- `Skija` — shapes, rects, circles, gradients, shadows, clipping, transforms, alpha
- `Gradient` — gradient direction helpers
- `SkijaFonts` — custom font registration
- `draw.SkijaText` — text: alignment, shadow, gradient, word-wrap
- `draw.SkijaImages` — images and Minecraft sprites/atlases
- `draw.SkijaItems` — rendering `ItemStack`s into the canvas
- `screen.SkijaScreen` — base class for a Skija-drawn Minecraft screen
- `screen.SkijaOverlay` — enable / route the single managed overlay source

See `org.blackaddons.blackskija.demo.SkijaDemo` for a full showcase.

## How it works

BlackSkija renders your draw calls into an off-screen texture it owns, then hands Minecraft the
finished texture to blit alongside the GUI. It never draws into MC's in-flight frame — which is
what lets it work on **Vulkan**, where you can't co-record into someone else's command buffer
(GL-only renderers rely on exactly that).

The catch with an off-screen texture is ordering our write before MC reads it. On OpenGL the
command stream is already ordered. On Vulkan, BlackSkija submits a small `vkCmdPipelineBarrier`
onto MC's shared graphics queue between its own submit and MC's blit — same-queue submission
order turns that into a write->read dependency with no CPU stall, so the overlay lands the **same
frame** (0 latency). The GPU backend is detected at runtime; on a GPU API it can't adapt the
overlay simply stays disabled.

The Skija native (~11 MB) isn't in the jar — it's downloaded once at startup from Maven Central
into a version-keyed cache, verified against an SHA-256 manifest baked at build time, and loaded
via the `skija.library.path` system property. Offline with a warm cache works; offline with an
empty cache leaves the overlay disabled until an online launch (never crashes).

## Building

```bash
./gradlew build              # remapped mod jar in build/libs/
./gradlew publishToMavenLocal # what JitPack runs
```

## License

[MIT](LICENSE.txt) © BlackAddons Team
