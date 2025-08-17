package com.github.edxref

import com.github.edxref.inspection.WSConsumerJavaInspection
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunsInEdt
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Before
import org.junit.Test

class MyAnnotationInspectionTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData" // adjust, if necessary

//    @Before
    override fun setUp() {
        super.setUp()
        System.setProperty("kotlin.script.disable.auto.import", "true")
        System.setProperty("kotlin.script.disable.compilation", "true")
        System.setProperty("idea.force.use.core.classloader.for.plugin.path", "true")
    }


    /**
     * Repeatedly dispatch pending events (which may include background PSI updates)
     * before performing highlighting. This helper will try for up to [maxIterations]
     * before giving up.
     */
    private fun waitForStableHighlighting(maxIterations: Int = 10) {
        var iterations = 0
        while (iterations < maxIterations) {
            try {
                ApplicationManager.getApplication().invokeAndWait {
                    myFixture.doHighlighting()
                    myFixture.checkHighlighting()
                }
                // Force highlighting and check that no PSI modifications occur.
                return // highlighting is stable!
            } catch (e: AssertionError) {
                // When the assertion is thrown due to PSI updates, let’s give it another try.
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                iterations++
            }
        }
        // One more call so that any persistent issue is reported.
        myFixture.doHighlighting()
        myFixture.checkHighlighting()
    }

//    @Test
    fun testPearlWebServiceConsumer_WithUrlAndProtocol() {
        val fileContent = """
            package test

            import com.example.WSConsumer
            import com.example.WSMsConsumer
            import com.example.WSMethods
            import com.example.LbMsType

            @WSConsumer(
                url = "http://example.com", 
                path = "http://claimservices/test/@value",
                method = WSMethods.GET, 
                msConsumer = @WSMsConsumer(value = LbMsType.LOCAL),
                sslCertificateValidation = true
            )
            class PearlWebServiceConsumer {}
        """.trimIndent()

        myFixture.configureByText("Test.kt", fileContent)
//        myFixture.enableInspections(MyAnnotationInspection())
        waitForStableHighlighting()  // use our helper instead of a direct call to checkHighlighting()
    }

//    @Test
//    @RunsInEdt
    fun testWebServiceConsumer_AllValuesOk() {
        val fileContent = """
            package test

            import com.example.WSConsumer
            import com.example.WSMsConsumer
            import com.example.WSMethods
            import com.example.LbMsType

            @WSConsumer(
                url = "http://example.com", 
                path = "claimservices/test/@value",
                method = WSMethods.POST,
                msConsumer = @WSMsConsumer(value = LbMsType.CRM),
                sslCertificateValidation = true
            )
            class WebServiceConsumer {}
        """.trimIndent()

        myFixture.configureByText("Test.kt", fileContent)
//        myFixture.enableInspections(MyAnnotationInspection())
        waitForStableHighlighting()
    }

//    @Test
    fun testAnnotationInspection() {
        val fileContent = """
        package test
        
        import com.example.WSConsumer
        import com.example.WSMsConsumer
        import com.example.WSMethods
        import com.example.LbMsType

        @WSConsumer(
            url = "http://example.com", 
            path = "http://claimservices/test/@value",
            method = WSMethods.GET, 
            msConsumer = @WSMsConsumer(value = LbMsType.LOCAL),
            sslCertificateValidation = true
        )
        class PearlWebServiceConsumer {}
    """.trimIndent()

        val file = myFixture.configureByText("Test.kt", fileContent)

        // Create inspection instance manually
        val inspection = WSConsumerJavaInspection()

        // Create problems holder
        val problemsHolder = ProblemsHolder(InspectionManager.getInstance(project), file, false)

        // Build visitor and run it on the file
        val visitor = inspection.buildVisitor(problemsHolder, false)
        visitor.visitFile(file);
        file.accept(visitor)

        // Check results
        val problems = problemsHolder.results
        // Assert on problems as needed
    }
}
