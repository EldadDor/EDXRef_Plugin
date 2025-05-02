// src/main/kotlin/com/github/edxref/icons/EDXRefIcons.kt
package com.github.edxref.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object EDXRefIcons {
    @JvmField
    val XML_TO_JAVA: Icon = IconLoader.getIcon("/icons/xmlToJava.svg", EDXRefIcons::class.java)

    @JvmField
    val JAVA_TO_XML: Icon = IconLoader.getIcon("/icons/javaToXml.svg", EDXRefIcons::class.java)

    @JvmField
    val METHOD_JAVA__TO_XML: Icon = IconLoader.getIcon("/icons/MethodQueryUtil.svg", EDXRefIcons::class.java)
}
