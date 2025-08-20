package com.github.edxref.inspection

/*
 * User: eadno1
 * Date: 19/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */

import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TempDirTestFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WSConsumerJavaInspectionPureJUnit5Test {

  private lateinit var myFixture: CodeInsightTestFixture
  private lateinit var tempDirFixture: TempDirTestFixture

  @BeforeEach
  fun setUp() {
    val factory = IdeaTestFixtureFactory.getFixtureFactory()
    val projectBuilder = factory.createLightFixtureBuilder("EDXRef Test")
    val codeInsightFixture = factory.createCodeInsightFixture(projectBuilder.fixture)

    tempDirFixture = factory.createTempDirTestFixture()
    tempDirFixture.setUp()

    myFixture = codeInsightFixture
    myFixture.setUp()

    // Set up test environment
    System.setProperty("idea.kotlin.plugin.use.k2", "true")

    // Enable inspection
    myFixture.enableInspections(WSConsumerJavaInspection::class.java)

    createModelClasses()
  }

  @AfterEach
  fun tearDown() {
    myFixture.tearDown()
    tempDirFixture.tearDown()
  }

  private fun createModelClasses() {
    // Same model class creation as before
    myFixture.configureByText(
      "WSConsumer.java",
      """
            package com.github.edxref.model;
            public @interface WSConsumer {
              String url() default "";
              String path() default "";
              // ... rest of annotation
            }
            """
        .trimIndent(),
    )
    // ... other model classes
  }

  @Test
  fun testInvalidUrlAndPathWithMsConsumer() {
    myFixture.configureByText(
      "TestFile.java",
      """
            package com.github.edxref.test;
            import com.github.edxref.model.*;
            
            @WSConsumer(url = "http://msdevcrm", path = "test", msConsumer = @WSMsConsumer(LbMsType.CRM))
            public interface TestInterface extends WebserviceConsumer {
            }
            """
        .trimIndent(),
    )

    val highlights = myFixture.doHighlighting()
    val warnings = highlights.filter { it.severity.name == "WARNING" }

    Assertions.assertTrue(warnings.isNotEmpty(), "Should find warnings")
  }
}
