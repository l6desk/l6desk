package io.github.l6desk.ui

import androidx.compose.ui.graphics.ImageBitmap

/** [this] as a PNG file, or null if it can't be encoded. */
expect fun ImageBitmap.encodePng(): ByteArray?
