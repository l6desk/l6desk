package io.github.l6desk.tools

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders art/l6desk-icon.svg (with Skia, which Compose already ships) to every raster icon the
 * apps need. Run from the repository root:  ./gradlew :desktopApp:generateIcons
 * The outputs are committed, so normal builds don't depend on this.
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".").canonicalFile
    val svg = File(root, "art/l6desk-icon.svg").readBytes()

    fun png(size: Int): ByteArray {
        val dom = SVGDOM(Data.makeFromBytes(svg))
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.scale(size / 512f, size / 512f)
        dom.setContainerSize(512f, 512f)
        dom.render(surface.canvas)
        return requireNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes
    }

    fun write(path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        println("wrote $path (${bytes.size} bytes)")
    }

    // Desktop: Linux package / window icon, and a Windows .ico with several sizes.
    write("desktopApp/icons/l6desk.png", png(512))
    write("desktopApp/src/main/resources/l6desk-icon.png", png(256))
    write("desktopApp/icons/l6desk.ico", ico(listOf(16, 24, 32, 48, 64, 128, 256).map { it to png(it) }))

    // Android launcher icons for API 24–25 (26+ use the adaptive icon in mipmap-anydpi-v26).
    mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192).forEach { (density, size) ->
        write("androidApp/src/main/res/mipmap-$density/ic_launcher.png", png(size))
    }
}

/** An .ico file holding PNG images (supported since Windows Vista). */
private fun ico(images: List<Pair<Int, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    val header = ByteBuffer.allocate(6 + 16 * images.size).order(ByteOrder.LITTLE_ENDIAN)
    header.putShort(0).putShort(1).putShort(images.size.toShort())
    var offset = 6 + 16 * images.size
    for ((size, data) in images) {
        val dim = if (size >= 256) 0 else size // 0 means 256
        header.put(dim.toByte()).put(dim.toByte()).put(0).put(0)
        header.putShort(1).putShort(32).putInt(data.size).putInt(offset)
        offset += data.size
    }
    out.write(header.array())
    images.forEach { out.write(it.second) }
    return out.toByteArray()
}
