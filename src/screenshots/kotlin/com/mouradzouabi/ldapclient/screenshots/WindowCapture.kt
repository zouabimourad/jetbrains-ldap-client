package com.mouradzouabi.ldapclient.screenshots

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import com.intellij.util.ui.JBUI
import javax.swing.JFrame
import javax.swing.RootPaneContainer

/**
 * Paints the IDE frame (and the dialogs / popups it owns) into an image. Painting instead of grabbing the
 * screen avoids the macOS screen-recording permission and does not depend on other windows on the desktop.
 */
object WindowCapture {
    fun capture(frame: JFrame, file: File, scale: Double = 2.0) {
        val content = frame.rootPane
        val origin = content.locationOnScreen
        val image = BufferedImage((content.width * scale).toInt(), (content.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.scale(scale, scale)
            content.paint(g)
            for (window in ownedWindows(frame)) paintWindow(g, window, origin.x, origin.y)
        } finally {
            g.dispose()
        }
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun paintWindow(g: Graphics2D, window: Window, originX: Int, originY: Int) {
        val root: Component = (window as? RootPaneContainer)?.rootPane ?: window
        val rootAt = root.locationOnScreen
        val windowAt = window.locationOnScreen
        // Native (OS drawn) title bar of dialogs is not painted by Swing: draw a simple one.
        val title = (window as? Dialog)?.takeIf { !it.isUndecorated }?.title
        val titleHeight = if (title != null) (rootAt.y - windowAt.y).coerceAtLeast(0) else 0
        val x = rootAt.x - originX
        val y = rootAt.y - originY - titleHeight
        val width = root.width
        val height = root.height + titleHeight
        val arc = if (title != null) 20.0 else 12.0

        val child = g.create() as Graphics2D
        try {
            child.translate(x, y)
            shadow(child, width, height, arc)
            child.clip(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), arc, arc))
            if (titleHeight > 0) titleBar(child, title.orEmpty(), width, titleHeight, root.background ?: UIUtil.getPanelBackground())
            child.translate(0, titleHeight)
            root.paint(child)
            // macOS dialogs with a transparent title bar: Swing paints an empty area, the OS draws the title.
            if (titleHeight == 0 && title != null && com.intellij.openapi.util.SystemInfoRt.isMac) {
                titleDecorations(child, title, width, JBUI.scale(28))
            }
        } finally {
            child.dispose()
        }
    }

    private fun titleBar(g: Graphics2D, title: String, width: Int, height: Int, background: Color) {
        g.color = background
        g.fillRect(0, 0, width, height)
        titleDecorations(g, title, width, height)
    }

    private fun titleDecorations(g: Graphics2D, title: String, width: Int, height: Int) {
        val colors = listOf(Color(0xFF5F57), Color(0xFEBC2E), Color(0x28C840))
        colors.forEachIndexed { i, color ->
            g.color = color
            g.fill(Ellipse2D.Double(14.0 + i * 20, height / 2.0 - 6, 12.0, 12.0))
        }
        g.font = JBFont.label().asBold()
        g.color = UIUtil.getLabelForeground()
        val metrics = g.fontMetrics
        g.drawString(title, (width - metrics.stringWidth(title)) / 2, (height - metrics.height) / 2 + metrics.ascent)
    }

    private fun shadow(g: Graphics2D, width: Int, height: Int, arc: Double) {
        for (i in 14 downTo 1) {
            g.color = Color(0, 0, 0, 5)
            g.fill(RoundRectangle2D.Double(-i.toDouble(), (-i + 6).toDouble(), (width + 2 * i).toDouble(), (height + 2 * i).toDouble(), arc + i, arc + i))
        }
    }

    /** Visible windows owned (directly or not) by [frame], in creation order. */
    fun ownedWindows(frame: JFrame): List<Window> = Window.getWindows().filter { w ->
        w !== frame && w.isShowing && w.width > 1 && w.height > 1 && generateSequence(w.owner) { it.owner }.any { it === frame }
    }
}
