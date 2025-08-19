package com.github.edxref.query.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.github.edxref.query.cache.QueryIndexService
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer

class CacheStatsDialog(
	private val project: Project,
	private val initialStats: Map<String, Any>
) : DialogWrapper(project) {

	private val queryIndexService = QueryIndexService.getInstance(project)
	private lateinit var statsTable: JBTable
	private lateinit var tableModel: DefaultTableModel
	private lateinit var refreshTimer: Timer
	private var autoRefresh = true

	init {
		title = "Query Index Cache Statistics"
		setSize(600, 400)
		init()
		startAutoRefresh()
	}

	override fun createCenterPanel(): JComponent {
		val mainPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
			border = JBUI.Borders.empty(10)
		}

		// Header with summary
		val headerPanel = createHeaderPanel()
		mainPanel.add(headerPanel, BorderLayout.NORTH)

		// Stats table
		val tablePanel = createTablePanel()
		mainPanel.add(tablePanel, BorderLayout.CENTER)

		// Control panel
		val controlPanel = createControlPanel()
		mainPanel.add(controlPanel, BorderLayout.SOUTH)

		return mainPanel
	}

	private fun createHeaderPanel(): JComponent {
		val panel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT))

		val stats = initialStats
		val hitRate = stats["hitRate"] as Int
		val totalRequests = (stats["cacheHits"] as Int) + (stats["cacheMisses"] as Int)

		val summaryLabel = JBLabel().apply {
			text = "<html><b>Cache Performance Summary:</b> ${hitRate}% hit rate from $totalRequests total requests</html>"
			font = font.deriveFont(Font.BOLD, 14f)
		}

		panel.add(summaryLabel)
		return panel
	}

	private fun createTablePanel(): JComponent {
		// Create custom table model - moved outside of apply block
		val customTableModel = object : DefaultTableModel(
			arrayOf("Metric", "Value", "Description"),
			0
		) {
			override fun isCellEditable(row: Int, column: Int): Boolean = false
		}

		tableModel = customTableModel

		statsTable = JBTable(tableModel).apply {
			// Set column widths
			columnModel.getColumn(0).preferredWidth = 150
			columnModel.getColumn(1).preferredWidth = 100
			columnModel.getColumn(2).preferredWidth = 300

			// Custom renderer for better formatting
			setDefaultRenderer(Object::class.java, StatsTableCellRenderer())

			// Enable row selection
			selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
		}

		updateTableData(initialStats)

		return JScrollPane(statsTable).apply {
			preferredSize = Dimension(550, 250)
		}
	}

	private fun createControlPanel(): JComponent {
		val panel = JBPanel<JBPanel<*>>(FlowLayout())

		val refreshButton = JButton("Refresh Now").apply {
			addActionListener { refreshStats() }
		}

		val autoRefreshCheckbox = JCheckBox("Auto Refresh (2s)", autoRefresh).apply {
			addActionListener {
				autoRefresh = isSelected
				if (autoRefresh) startAutoRefresh() else stopAutoRefresh()
			}
		}

		val clearCacheButton = JButton("Clear Cache").apply {
			addActionListener { clearCache() }
		}

		val exportButton = JButton("Export Stats").apply {
			addActionListener { exportStats() }
		}

		panel.add(refreshButton)
		panel.add(autoRefreshCheckbox)
		panel.add(clearCacheButton)
		panel.add(exportButton)

		return panel
	}

	private fun updateTableData(stats: Map<String, Any>) {
		SwingUtilities.invokeLater {
			tableModel.rowCount = 0 // Clear existing data

			// Add rows with descriptions
			addStatRow("Hit Rate", "${stats["hitRate"]}%", "Percentage of cache hits vs total requests")
			addStatRow("Cache Hits", "${stats["cacheHits"]}", "Number of successful cache lookups")
			addStatRow("Cache Misses", "${stats["cacheMisses"]}", "Number of cache misses requiring index lookup")
			addStatRow("XML Tag Cache", "${stats["xmlTagCacheSize"]} items", "Number of cached XML query tags")
			addStatRow("Interface Cache", "${stats["interfaceCacheSize"]} items", "Number of cached Java interfaces")

			// Calculate additional metrics
			val totalRequests = (stats["cacheHits"] as Int) + (stats["cacheMisses"] as Int)
			val totalCacheSize = (stats["xmlTagCacheSize"] as Int) + (stats["interfaceCacheSize"] as Int)
			val estimatedMemoryKB = (totalCacheSize * 0.5).toInt()

			addStatRow("Total Requests", "$totalRequests", "Total number of cache requests made")
			addStatRow("Total Cache Size", "$totalCacheSize items", "Combined size of all caches")
			addStatRow("Est. Memory Usage", "${estimatedMemoryKB} KB", "Estimated memory usage by caches")

			// Performance indicators
			val performanceStatus = when {
				stats["hitRate"] as Int >= 80 -> "Excellent"
				stats["hitRate"] as Int >= 60 -> "Good"
				stats["hitRate"] as Int >= 40 -> "Fair"
				else -> "Poor"
			}
			addStatRow("Performance", performanceStatus, "Overall cache performance rating")
		}
	}

	private fun addStatRow(metric: String, value: String, description: String) {
		tableModel.addRow(arrayOf(metric, value, description))
	}

	private fun refreshStats() {
		val newStats = queryIndexService.getCacheStats()
		updateTableData(newStats)
	}

	private fun startAutoRefresh() {
		if (::refreshTimer.isInitialized) {
			refreshTimer.stop()
		}
		refreshTimer = Timer(2000) { refreshStats() }
		refreshTimer.start()
	}

	private fun stopAutoRefresh() {
		if (::refreshTimer.isInitialized) {
			refreshTimer.stop()
		}
	}

	private fun clearCache() {
		val result = JOptionPane.showConfirmDialog(
			contentPanel,
			"Are you sure you want to clear all caches?\nThis will temporarily reduce performance until caches are rebuilt.",
			"Clear Cache Confirmation",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		)

		if (result == JOptionPane.YES_OPTION) {
			queryIndexService.clearCaches()
			refreshStats()
			JOptionPane.showMessageDialog(
				contentPanel,
				"Cache cleared successfully!",
				"Cache Cleared",
				JOptionPane.INFORMATION_MESSAGE
			)
		}
	}

	private fun exportStats() {
		val stats = queryIndexService.getCacheStats()
		val exportText = buildString {
			appendLine("Query Index Cache Statistics Export")
			appendLine("Generated: ${java.time.LocalDateTime.now()}")
			appendLine("=".repeat(50)) // Fixed: Use repeat() instead of times operator
			appendLine()

			stats.forEach { (key, value) ->
				appendLine("$key: $value")
			}

			appendLine()
			appendLine("Performance Analysis:")
			val hitRate = stats["hitRate"] as Int
			when {
				hitRate >= 80 -> appendLine("✓ Excellent cache performance")
				hitRate >= 60 -> appendLine("✓ Good cache performance")
				hitRate >= 40 -> appendLine("⚠ Fair cache performance - consider optimization")
				else -> appendLine("⚠ Poor cache performance - optimization needed")
			}
		}

		// Copy to clipboard
		val clipboard = Toolkit.getDefaultToolkit().systemClipboard
		clipboard.setContents(java.awt.datatransfer.StringSelection(exportText), null)

		JOptionPane.showMessageDialog(
			contentPanel,
			"Cache statistics exported to clipboard!",
			"Export Complete",
			JOptionPane.INFORMATION_MESSAGE
		)
	}

	override fun createActions(): Array<Action> {
		return arrayOf(okAction)
	}

	override fun dispose() {
		stopAutoRefresh()
		super.dispose()
	}
}

// Custom cell renderer for better visual formatting - moved outside the class
private class StatsTableCellRenderer : DefaultTableCellRenderer() {
	override fun getTableCellRendererComponent(
		table: JTable,
		value: Any?,
		isSelected: Boolean,
		hasFocus: Boolean,
		row: Int,
		column: Int
	): Component {
		val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

		// Color coding for performance metrics
		if (column == 1 && value.toString().contains("%")) {
			val percentage = value.toString().replace("%", "").toIntOrNull() ?: 0
			background = when {
				isSelected -> table.selectionBackground
				percentage >= 80 -> Color(200, 255, 200) // Light green
				percentage >= 60 -> Color(255, 255, 200) // Light yellow
				percentage >= 40 -> Color(255, 230, 200) // Light orange
				else -> Color(255, 200, 200) // Light red
			}
		} else if (!isSelected) {
			background = table.background
		}

		return component
	}
}
