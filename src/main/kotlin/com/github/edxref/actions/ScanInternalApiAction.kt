package com.github.edxref.actions // Or your preferred package

import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
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
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

private val LOG = logger<ScanInternalApiAction>()

class ScanInternalApiAction : AnAction() {

    // Ensure update() runs on BGT (Background Thread) for potentially slow checks
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * Enable the action only if a project is open.
     */
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    /**
     * Executed when the action is triggered.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val internalApiFqn = ReadAction.compute<String, Throwable> {
            project.getWSConsumerSettings().internalApiAnnotationFqn
        }.ifBlank {
            showErrorNotification(project, "@InternalApi FQN is not configured in settings.")
            return
        }

        LOG.info("Starting @InternalApi scan for FQN: $internalApiFqn")

        // Run the potentially long-running scan in a background task with a progress indicator
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning for @InternalApi Usages", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false // Use determinate progress if possible, though calculating total is hard
                indicator.text = "Finding @InternalApi annotation class..."

                val results = mutableMapOf<String, MutableList<String>>() // Type -> List of element names
                val annotatedClasses = mutableListOf<String>()
                val annotatedInterfaces = mutableListOf<String>()
                val annotatedMethods = mutableListOf<String>()

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
                var processedCount = 0 // Simple progress tracking

                // Search for all references (usages) of the annotation class
                // This search itself needs to run within a ReadAction
                ReadAction.run<Throwable> {
                    ReferencesSearch.search(annotationClass, GlobalSearchScope.projectScope(project)).forEach { psiReference ->
                        indicator.checkCanceled() // Check frequently if the user cancelled
                        processedCount++
                        indicator.fraction = 0.0 // Fraction is hard to calculate, just update text
                        indicator.text = "Processing usage $processedCount..."

                        val element = psiReference.element // The element where the reference occurs (often the annotation name)
                        val containingFile = element.containingFile

                        // Only process Java files as requested
                        if (containingFile is PsiJavaFile) {
                            // Find the element actually being annotated (Class, Interface, Method)
                            val annotatedElement = PsiTreeUtil.getParentOfType(
                                element,
                                PsiClass::class.java, // Includes classes and interfaces
                                PsiMethod::class.java
                            )

                            when (annotatedElement) {
                                is PsiClass -> {
                                    val name = annotatedElement.qualifiedName ?: annotatedElement.name ?: "anonymous"
                                    if (annotatedElement.isInterface) {
                                        annotatedInterfaces.add(name)
                                        LOG.debug("Found @InternalApi on Interface: $name")
                                    } else {
                                        annotatedClasses.add(name)
                                        LOG.debug("Found @InternalApi on Class: $name")
                                    }
                                }

                                is PsiMethod -> {
                                    val className = annotatedElement.containingClass?.qualifiedName ?: "unknown class"
                                    val methodName = annotatedElement.name
                                    val signature = "$className#$methodName"
                                    annotatedMethods.add(signature)
                                    LOG.debug("Found @InternalApi on Method: $signature")
                                }

                                else -> {
                                    LOG.debug("Found @InternalApi on unexpected element type: ${element.text} in ${containingFile.name}")
                                }
                            }
                        }
                    } // End forEach reference
                } // End ReadAction

                indicator.text = "Scan complete. Preparing results..."
                LOG.info("Scan finished. Found: ${annotatedClasses.size} classes, ${annotatedInterfaces.size} interfaces, ${annotatedMethods.size} methods.")

                // Show results on the UI thread
                ApplicationManager.getApplication().invokeLater {
                    if (indicator.isCanceled) return@invokeLater // Don't show results if cancelled

                    val message = buildString {
                        appendLine("Scan for @InternalApi ($internalApiFqn) complete:")
                        appendLine("- Found on ${annotatedClasses.size} classes.")
                        appendLine("- Found on ${annotatedInterfaces.size} interfaces.")
                        appendLine("- Found on ${annotatedMethods.size} methods.")
                        // Optionally list the first few findings
                        if (annotatedClasses.isNotEmpty()) appendLine("\nClasses:\n${annotatedClasses.take(10).joinToString("\n")}${if (annotatedClasses.size > 10) "\n..." else ""}")
                        if (annotatedInterfaces.isNotEmpty()) appendLine("\nInterfaces:\n${annotatedInterfaces.take(10).joinToString("\n")}${if (annotatedInterfaces.size > 10) "\n..." else ""}")
                        if (annotatedMethods.isNotEmpty()) appendLine("\nMethods:\n${annotatedMethods.take(10).joinToString("\n")}${if (annotatedMethods.size > 10) "\n..." else ""}")
                    }
                    // Use Notifications API for better display than Messages
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("EDXRef Notifications") // Use a consistent group ID
                        .createNotification("Internal API Scan", message, NotificationType.INFORMATION)
                        .notify(project)

                    // Alternative: Simple message dialog (less ideal for lots of results)
                    // Messages.showInfoMessage(project, message, "Internal API Scan Results")
                }
            } // End run(indicator)
        }) // End ProgressManager.run
    } // End actionPerformed

    private fun showErrorNotification(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("EDXRef Notifications") // Use a consistent group ID
                .createNotification("Internal API Scan Error", message, NotificationType.ERROR)
                .notify(project)
        }
    }
}
