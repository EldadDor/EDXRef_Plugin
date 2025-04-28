package com.github.edxref.actions // Or your preferred package

import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.find.FindManager
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.find.impl.FindManagerImpl
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.*
import com.intellij.util.CommonProcessors

private val LOG = logger<ScanInternalApiAction>()

class ScanInternalApiAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val internalApiFqn = ReadAction.compute<String, Throwable> {
            project.getWSConsumerSettings().internalApiAnnotationFqn
        }.ifBlank {
            showErrorNotification(project, "@InternalApi FQN is not configured in settings.")
            return
        }

        LOG.info("Starting @InternalApi scan for FQN: $internalApiFqn")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning for @InternalApi Usages", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true // Search can be indeterminate
                indicator.text = "Finding @InternalApi annotation class..."

                // Find the annotation class itself (must be done in ReadAction)
                val annotationClass = ReadAction.compute<PsiClass?, Throwable> {
                    JavaPsiFacade.getInstance(project).findClass(internalApiFqn, GlobalSearchScope.allScope(project))
                }

                if (annotationClass == null) {
                    LOG.warn("@InternalApi annotation class not found: $internalApiFqn")
                    showErrorNotification(project, "@InternalApi annotation class not found: $internalApiFqn")
                    return // Stop the task
                }

                indicator.text = "Searching for annotation usages..."

                // --- Use FindUsages infrastructure ---
                // Create a processor to collect usages into UsageInfo objects
                val usageProcessor = CommonProcessors.CollectProcessor<UsageInfo>()
                val searchScope = GlobalSearchScope.projectScope(project) // Define search scope

                // Create FindUsagesOptions based on the scope
                val findUsagesOptions = FindUsagesOptions(searchScope)
                findUsagesOptions.isUsages = true // We are looking for usages
                findUsagesOptions.isSearchForTextOccurrences = false // Don't search text

                // Use a FindUsagesHandler for the annotation class
                // This leverages IntelliJ's optimized search mechanisms
                val findUsagesHandler = (FindManager.getInstance(project) as FindManagerImpl)
                    .findUsagesManager.getFindUsagesHandler(annotationClass, false)

                if (findUsagesHandler == null) {
                    LOG.error("Could not get FindUsagesHandler for $internalApiFqn")
                    showErrorNotification(project, "Failed to initialize usage search.")
                    return
                }

                // Perform the search using the handler and options
                // This needs to run within a ReadAction implicitly handled by processElementUsages
                val success = findUsagesHandler.processElementUsages(
                    annotationClass,
                    usageProcessor,
                    findUsagesOptions
                )

                if (!success) {
                    LOG.warn("Usage search process did not complete successfully.")
                    // Optionally notify user, though it might have been cancelled
                    if (!indicator.isCanceled) {
                        showErrorNotification(project, "Usage search failed or was interrupted.")
                    }
                    return
                }

                val usages = usageProcessor.results.map { UsageInfo2UsageAdapter(it) }.toTypedArray()
                LOG.info("Scan finished. Found ${usages.size} usages.")

                // --- Show results in UsageView ---
                ApplicationManager.getApplication().invokeLater {
                    if (indicator.isCanceled) return@invokeLater

                    if (usages.isEmpty()) {
                        showInfoNotification(project, "No usages of @InternalApi ($internalApiFqn) found in project scope.")
                        return@invokeLater
                    }

                    // Define how the UsageView should look
                    val presentation = UsageViewPresentation()
                    presentation.tabText = "@InternalApi Usages"
                    presentation.toolwindowTitle = "Usages of @InternalApi ($internalApiFqn)"
                    presentation.setUsagesString("usages of @InternalApi")
                    presentation.scopeText = searchScope.displayName
                    presentation.isOpenInNewTab = false
                    presentation.isCodeUsages = true

                    // Show the standard UsageView tool window
                    UsageViewManager.getInstance(project).showUsages(
                        UsageTarget.EMPTY_ARRAY, // Targets are often the element searched for, but UsageInfo has elements
                        usages,
                        presentation
                    )
                }
            } // End run(indicator)
        }) // End ProgressManager.run
    } // End actionPerformed

    private fun showErrorNotification(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("EDXRef Notifications") // Use consistent group ID
                .createNotification("Internal API Scan Error", message, NotificationType.ERROR)
                .notify(project)
        }
    }

    private fun showInfoNotification(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("EDXRef Notifications") // Use consistent group ID
                .createNotification("Internal API Scan", message, NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
