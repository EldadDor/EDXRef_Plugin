package com.github.edxref.inspection

/*
 * User: eadno1
 * Date: 17/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */
import com.github.edxref.settings.WSConsumerSettings.Companion.getWSConsumerSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.*

/**
 * Common utilities for inspections, especially for K2 Mode compatibility. Centralizes settings
 * access, annotation handling, and validation patterns.
 */
object InspectionUtil {

  // --- Settings Accessors ---
  fun getSettings(project: Project) = project.getWSConsumerSettings()

  fun getWsConsumerAnnotationFqn(project: Project) =
    getSettings(project).wsConsumerAnnotationFqn.ifBlank {
      "com.github.edxref.annotations.WSConsumer"
    }

  fun getWebserviceConsumerFqn(project: Project) =
    getSettings(project).webserviceConsumerFqn.ifBlank {
      "com.github.edxref.annotations.WebserviceConsumer"
    }

  fun getPearlWebserviceConsumerFqn(project: Project) =
    getSettings(project).pearlWebserviceConsumerFqn.ifBlank {
      "com.github.edxref.annotations.PearlWebserviceConsumer"
    }

  fun getWsHeaderFqn(project: Project) =
    getSettings(project).wsHeaderAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSHeader" }

  fun getWsHeadersFqn(project: Project) =
    getSettings(project).wsHeadersAnnotationFqn.ifBlank {
      "com.github.edxref.annotations.WSHeaders"
    }

  fun getWsParamFqn(project: Project) =
    getSettings(project).wsParamAnnotationFqn.ifBlank { "com.github.edxref.annotations.WSParam" }

  // --- Logging ---
  fun logIfEnabled(project: Project, logger: Logger, message: String) {
    try {
      if (getSettings(project).enableLog) logger.info(message)
    } catch (e: Exception) {
      // Ignore silently
    }
  }

  // --- Webservice Consumer Detection ---
  fun isWebserviceConsumer(psiClass: PsiClass): Boolean {
    val project = psiClass.project
    val wsConsumerFqn = getWebserviceConsumerFqn(project)
    val pearlConsumerFqn = getPearlWebserviceConsumerFqn(project)
    return (wsConsumerFqn.isNotBlank() && InheritanceUtil.isInheritor(psiClass, wsConsumerFqn)) ||
      (pearlConsumerFqn.isNotBlank() && InheritanceUtil.isInheritor(psiClass, pearlConsumerFqn))
  }

  // --- Java Annotation Attribute Extraction ---
  fun getAnnotationStringAttribute(annotation: PsiAnnotation, attributeName: String): String? {
    val attrValue = annotation.findAttributeValue(attributeName)
    return if (attrValue is PsiLiteralExpression && attrValue.value is String)
      attrValue.value as String
    else null
  }

  // --- Kotlin Annotation Attribute Extraction (K2-compatible) ---
  fun getKotlinAnnotationStringAttribute(
    annotation: KtAnnotationEntry,
    attributeName: String,
  ): String? {
    val arg =
      annotation.valueArguments.find { it.getArgumentName()?.asName?.asString() == attributeName }
        ?: return null
    val expr = arg.getArgumentExpression()
    val text = expr?.text ?: return null
    return if (expr is KtStringTemplateExpression && text.startsWith("\"") && text.endsWith("\"")) {
      text.substring(1, text.length - 1)
    } else {
      text
    }
  }

  // --- K2-Compatible Annotation Checking ---
  /**
   * Checks if a Kotlin class/object has an annotation by short name. More reliable than light class
   * conversion for K2 Mode.
   */
  fun hasKotlinAnnotation(classOrObject: KtClassOrObject, annotationShortName: String): Boolean {
    return classOrObject.annotationEntries.any { it.shortName?.asString() == annotationShortName }
  }

  /** Finds a Kotlin annotation by short name. */
  fun findKotlinAnnotation(
    classOrObject: KtClassOrObject,
    annotationShortName: String,
  ): KtAnnotationEntry? {
    return classOrObject.annotationEntries.find { it.shortName?.asString() == annotationShortName }
  }

  /** Finds a Kotlin annotation on a function by short name. */
  fun findKotlinFunctionAnnotation(
    function: KtNamedFunction,
    annotationShortName: String,
  ): KtAnnotationEntry? {
    return function.annotationEntries.find { it.shortName?.asString() == annotationShortName }
  }

  // --- FQN to Short Name Conversion ---
  fun getShortName(fqn: String): String = fqn.substringAfterLast('.')

  // --- K2-Compatible WSConsumer Detection ---
  /**
   * Checks if a Kotlin class has WSConsumer annotation without relying on light class conversion.
   */
  fun hasWSConsumerAnnotation(project: Project, classOrObject: KtClassOrObject): Boolean {
    val wsConsumerShortName = getShortName(getWsConsumerAnnotationFqn(project))
    return hasKotlinAnnotation(classOrObject, wsConsumerShortName)
  }

  /**
   * Combined check for both WSConsumer annotation and webservice consumer inheritance. Falls back
   * to light class only if needed for inheritance check.
   */
  fun isKotlinWebserviceConsumer(classOrObject: KtClassOrObject): Boolean {
    val project = classOrObject.project

    // First check for WSConsumer annotation (K2-compatible)
    if (!hasWSConsumerAnnotation(project, classOrObject)) return false

    // Then check inheritance (may need light class)
    val lightClass = classOrObject.toLightClass()
    return lightClass?.let { isWebserviceConsumer(it) } ?: false
  }
}
