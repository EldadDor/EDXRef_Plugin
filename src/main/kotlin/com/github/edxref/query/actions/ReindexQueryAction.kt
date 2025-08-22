package com.github.edxref.query.actions

/*
 * User: eldad
 * Date: 8/20/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */

import com.github.edxref.query.cache.QueryIndexService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class ReindexQueryAction : AnAction("Reindex QueryRef Indexes") {

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return

    val indexService = QueryIndexService.getInstance(project)
    indexService.forceReindex()

    // Optional: Show notification
    com.intellij.notification.NotificationGroupManager.getInstance()
      .getNotificationGroup("QueryRef")
      ?.createNotification(
        "QueryRef indexes rebuild requested",
        com.intellij.notification.NotificationType.INFORMATION,
      )
      ?.notify(project)
  }
}
