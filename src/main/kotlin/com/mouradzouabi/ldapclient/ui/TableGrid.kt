package com.mouradzouabi.ldapclient.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import javax.swing.JTable

/** Spreadsheet-style grid shared by all tables of the plugin. */
object TableGrid {
    // Translucent so the lines stay visible over selected and hovered rows, whose backgrounds are close to Table.gridColor.
    val LINE_COLOR = JBColor(Color(0, 0, 0, 0x17), Color(0, 0, 0, 0x60))
    val SECTION_COLOR = JBColor(Color(0, 0, 0, 0x30), Color(255, 255, 255, 0x24))

    fun apply(table: JTable, horizontalLines: Boolean = true) {
        table.gridColor = LINE_COLOR
        table.setShowVerticalLines(true)
        table.setShowHorizontalLines(horizontalLines)
        table.intercellSpacing = Dimension(1, if (horizontalLines) 1 else 0)
    }
}
