package org.blackaddons.blackskija.backend.natives

// Maps the running OS/arch to a Skija native classifier (e.g., windows-x64) and the files its loader needs.
internal object NativePlatform {

    val current: String? by lazy { detect() }

    fun osOf(classifier: String): String = classifier.substringBefore('-')

    fun archOf(classifier: String): String = classifier.substringAfter('-')

    fun neededFiles(os: String): List<String> = buildList {
        add(libFileName(os))
        if (os == "windows") add("icudtl.dat")
    }

    private fun libFileName(os: String): String = when (os) {
        "windows" -> "skija.dll"
        "linux" -> "libskija.so"
        "macos" -> "libskija.dylib"
        else -> error("unknown os: $os")
    }

    private fun detect(): String? {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val os = when {
            osName.contains("win") -> "windows"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("nux") || osName.contains("nix") -> "linux"
            else -> return null
        }
        val arch = when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
            osArch.contains("amd64") || osArch.contains("x86_64") || osArch == "x64" -> "x64"
            else -> return null
        }
        return "$os-$arch"
    }
}
