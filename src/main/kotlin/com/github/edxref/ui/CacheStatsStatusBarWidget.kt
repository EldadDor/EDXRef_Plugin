/*
 * User: eadno1
 * Date: 19/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */
package com.github.edxref.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.github.edxref.query.cache.QueryIndexService
import com.github.edxref.query.ui.CacheStatsDialog
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.event.MouseEvent
import javax.swing.Timer

class CacheStatsStatusBarWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

	companion object {
		const val ID = "QueryIndexCacheStats"
	}

	private val queryIndexService = QueryIndexService.getInstance(project)
	private val updateTimer = Timer(2000) { updateStats() } // Update every 2 seconds

	init {
		updateTimer.start()
	}

	override fun ID(): String = ID

	override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

	override fun getText(): String {
		val stats = queryIndexService.getCacheStats()
		val hitRate = stats["hitRate"] as Int
		val totalSize = (stats["xmlTagCacheSize"] as Int) + (stats["interfaceCacheSize"] as Int)

		return "Cache: ${hitRate}% hit, $totalSize items"
	}

	override fun getTooltipText(): String {
		val stats = queryIndexService.getCacheStats()
		return buildString {
			appendLine("Query Index Cache Statistics:")
			appendLine("Hit Rate: ${stats["hitRate"]}%")
			appendLine("Cache Hits: ${stats["cacheHits"]}")
			appendLine("Cache Misses: ${stats["cacheMisses"]}")
			appendLine("XML Tags: ${stats["xmlTagCacheSize"]} items")
			appendLine("Interfaces: ${stats["interfaceCacheSize"]} items")
			appendLine("Click for detailed view")
		}
	}

	override fun getClickConsumer(): Consumer<MouseEvent>? {
		return Consumer {
			showDetailedStatsDialog()
		}
	}

	// Required implementation for TextPresentation
	override fun getAlignment(): Float = 0.5f // Center alignment

	private fun updateStats() {
		myStatusBar?.updateWidget(ID)
	}

	private fun showDetailedStatsDialog() {
		CacheStatsDialog(project, queryIndexService.getCacheStats()).show()
	}

	override fun dispose() {
		updateTimer.stop()
		super.dispose()
	}
}

class CacheStatsStatusBarWidgetFactory : StatusBarWidgetFactory {
	override fun getId(): String = CacheStatsStatusBarWidget.ID

	override fun getDisplayName(): String = "Query Cache Stats"

	override fun isAvailable(project: Project): Boolean = true

	override fun createWidget(project: Project): StatusBarWidget {
		return CacheStatsStatusBarWidget(project)
	}

	override fun disposeWidget(widget: StatusBarWidget) {
		widget.dispose()
	}

	override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
