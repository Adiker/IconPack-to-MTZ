package io.github.adiker.iconpacktomtz.core.mtz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File

object PreviewGenerator {
    fun create(iconFiles: List<File>, title: String): ByteArray {
        val width = 720
        val height = 1280
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(28, 29, 32))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 42f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(title.take(40), width / 2f, 100f, paint)
            val iconSize = 132
            val horizontalGap = 28
            val verticalGap = 40
            val startX = (width - (4 * iconSize + 3 * horizontalGap)) / 2
            val startY = 180
            iconFiles.take(24).forEachIndexed { index, file ->
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: return@forEachIndexed
                try {
                    val column = index % 4
                    val row = index / 4
                    val left = startX + column * (iconSize + horizontalGap)
                    val top = startY + row * (iconSize + verticalGap)
                    canvas.drawBitmap(
                        source,
                        null,
                        Rect(left, top, left + iconSize, top + iconSize),
                        null,
                    )
                } finally {
                    source.recycle()
                }
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
