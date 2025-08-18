package com.github.edxref.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.psi.*
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WSConsumerJavaInspectionKotlin2Test : LightJavaCodeInsightFixtureTestCase() {

  override fun getTestDataPath(): String {
    // Specify the path to your test data directory
    return "src/test/testData"
  }

  @Test
  fun testMissingUrlAndPath() {
    // Create a simplified test file
    val file =
      myFixture.configureByText(
        "TestFile.java",
        """
        public interface TestInterface {
        }
    """,
      ) as PsiJavaFile

    val inspection = WSConsumerJavaInspection()
    val manager = InspectionManager.getInstance(project)

    // Mock a PsiClass with an annotation
    val psiClass = mock(PsiClass::class.java)
    val annotation = mock(PsiAnnotation::class.java)

    `when`(psiClass.annotations).thenReturn(arrayOf(annotation))
    `when`(annotation.qualifiedName).thenReturn("com.github.edxref.WSConsumer")
    `when`(annotation.findAttributeValue("url")).thenReturn(null)
    `when`(annotation.findAttributeValue("path")).thenReturn(null)

    // Create mock PsiLiteralExpression for null values
    val nullLiteral = mock<PsiLiteralExpression>()
    whenever(nullLiteral.text).thenReturn("null")
    whenever(annotation.findAttributeValue("url")).thenReturn(nullLiteral)
    whenever(annotation.findAttributeValue("path")).thenReturn(nullLiteral)

    // Run the inspection directly
    val problems = inspection.checkClass(psiClass, manager, true)

    // Assert that the inspection found an issue - Use fully qualified JUnit 5 assertions
    assertNotNull(problems, "Problems should not be null")
    assertTrue(problems.isNotEmpty(), "Should find problems with missing url and path")
  }

  @Test
  fun testMissingUrlAndPath2() {
    // Create a real inspection instance
    val inspection = WSConsumerJavaInspection()

    // Create mocks
    val psiClass = mock<PsiClass>()
    val annotation = mock<PsiAnnotation>()
    val manager = InspectionManager.getInstance(project)

    // Set up the mock behavior with more detailed configuration
    whenever(psiClass.annotations).thenReturn(arrayOf(annotation))
    whenever(annotation.qualifiedName).thenReturn("WSConsumer")

    // Debug prints to see what's happening
    println("Setting up mocks for WSConsumer annotation")

    // Make sure findAttributeValue returns null for both url and path
    whenever(annotation.findAttributeValue("url")).thenReturn(null)
    whenever(annotation.findAttributeValue("path")).thenReturn(null)

    // Execute the inspection directly
    println("Executing inspection.checkClass")
    val problems = inspection.checkClass(psiClass, manager, true)

    // Print debug info about the result
    println("Problems found: ${problems.size}")
    if (problems.isEmpty()) {
      println("No problems were detected by the inspection. Likely issues:")
      println("1. The inspection may not be recognizing the annotation (check qualifiedName)")
      println("2. The inspection logic may not be triggering for null values")
      println("3. There might be additional conditions in the inspection we haven't mocked")
    } else {
      println("Problems: ${problems.joinToString { it.descriptionTemplate }}")
    }

    // Verify the result - Use fully qualified JUnit 5 assertions
    assertTrue(
      problems.isNotEmpty(),
      "Should find problems with missing url and path, but none were found",
    )
  }
}
