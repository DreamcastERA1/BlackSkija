# BlackSkija

# NOT FINISHED RIGHT NOW, YOU PROBABLY SHOULD WAIT BEFORE I RELEASE NEW, MUCH BETTER VERSION

GPU-accelerated UI rendering for Minecraft (Fabric), powered by [Skija](https://github.com/HumbleUI/Skija)
(the JVM bindings to Google's [Skia](https://skia.org/)). BlackSkija draws your UI onto a
Skija `Canvas` and composites the result over Minecraft's frame — on **Vulkan** or OpenGL —
so you get anti-aliased shapes, gradients, blur, drop shadows and rich text without fighting
`blaze3d`.

> **Client-side mod / library.** BlackSkija is meant to be used as a dependency by other
> mods that want a real 2D graphics API for their screens and overlays.

## Requirements

| | |
|---|---|
| Minecraft | `26.2` |
| Fabric Loader | `>= 0.19.3` |
| Fabric API | required |
| Fabric Language Kotlin | `>= 1.13.12+kotlin.2.4.0` |
| Java | 25 |

The Skija native library is **not** bundled in the jar — it is downloaded at runtime and
verified against a SHA-256 manifest generated at build time, so only the small `skija-shared`
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

Subclass `SkijaScreen` and draw with the `Skija` API in gui-scaled coordinates — a frame is
already open for you:

```kotlin
import net.minecraft.network.chat.Component
import org.blackaddons.blackskija.api.Gradient
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaText
import org.blackaddons.blackskija.api.SkijaScreen
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

Open it like any Minecraft screen (`Minecraft.getInstance().setScreen(MyScreen())`). The
world keeps rendering behind it; ESC closes it. For an over-gameplay HUD instead of a screen,
drive `SkijaCompositor` directly.

API surface lives in `org.blackaddons.blackskija.api`:

- `Skija` — shapes, rects, circles, gradients, shadows, clipping, transforms, alpha
- `SkijaText` / `SkijaFonts` — text and custom fonts
- `SkijaImages` — images and Minecraft sprites/atlases
- `SkijaItems` — rendering `ItemStack`s into the canvas
- `Gradient` — gradient direction helpers

See `org.blackaddons.blackskija.demo.SkijaDemo` for a full showcase.

## Building

```bash
./gradlew build              # remapped mod jar in build/libs/
./gradlew publishToMavenLocal # what JitPack runs
```

## License

[MIT](LICENSE.txt) © BlackAddons Team
