package com.github.edxref


import com.github.edxref.inspection.MyAnnotationInspection2
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import org.junit.Test

class MyAnnotationInspectionTest : LightPlatformCodeInsightFixtureTestCase() {

    override fun getTestDataPath(): String = "src/test/testData" // You can leave this empty if you're using inlined code

    @Test
    fun testPearlWebServiceConsumer_WithUrlAndProtocol() {
        // This code should trigger problems:
        // - The parent annotation (WSConsumer) has a non-empty URL (it should use path only).
        // - The path contains the protocol "http://".
        // - If msConsumer is present and we're in PearlWebServiceConsumer, then additional checks apply.
        val fileContent = """
            package test

            // Import statements for your annotations and enums
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
        myFixture.enableInspections(MyAnnotationInspection2())
        // checkHighlighting() ensures all issues are detected.
        myFixture.checkHighlighting()
    }

    @Test
    fun testWebServiceConsumer_AllValuesOk() {
        // This test simulates correct usage on WebServiceConsumer, so no problems should be reported.
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
        myFixture.enableInspections(MyAnnotationInspection2())
        myFixture.checkHighlighting()
    }
}