package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class WSConsumerJavaInspectionKotlinTest : BasePlatformTestCase() {

  override fun getTestDataPath(): String {
    return "src/test/testData"
  }

  override fun setUp() {
    super.setUp()
    // Add the model classes to the test module
    addModelClassesToModule()
    myFixture.enableInspections(WSConsumerJavaInspection::class.java)
  }

  private fun addModelClassesToModule() {
    // Add all necessary model classes to the test module
    val modelDir = "com/github/edxref/model"
    myFixture.copyDirectoryToProject(modelDir, modelDir)
  }

  /* @Test
  fun testInvalidUrlAndPathWithMsConsumer2() {
      // Add expected highlighting information in the test code
      myFixture.configureByText("LbInValidUrlModel3.java", """
      package com.github.edxref.test;

      import com.github.edxref.model.*;

      <warning descr="For @WSConsumer with msConsumer, 'url' must not be specified; use 'path' only."><warning descr="Invalid URL: 'msdevcrm' is in the list of restricted servers.">@WSConsumer(url = "http://msdevcrm", path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000, msConsumer = @WSMsConsumer(LbMsType.CRM))</warning></warning>
      public interface LbInValidUrlModel3 extends WebserviceConsumer {
      }
  """.trimIndent())

      myFixture.enableInspections(WSConsumerJavaInspection())
      myFixture.checkHighlighting(true, false, true)
  }*/

  @Test
  fun testInvalidUrlAndPathWithMsConsumer2() {
    // Configure the test file without embedded warnings
    myFixture.configureByText(
      "LbInValidUrlModel3.java",
      """
    package com.github.edxref.test;
    
    import com.github.edxref.model.*;
    
    @WSConsumer(url = "http://msdevcrm", path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000, msConsumer = @WSMsConsumer(LbMsType.CRM))
    public interface LbInValidUrlModel3 extends WebserviceConsumer {
    }
    """
        .trimIndent(),
    )

    myFixture.enableInspections(WSConsumerJavaInspection())

    // Get the highlighting results
    val highlights = myFixture.doHighlighting()

    // Verify the expected warnings are present
    val warnings = highlights.filter { it.severity.name == "WARNING" }

    // Get the expected messages from MyBundle
    val expectedUrlWithMsConsumer = MyBundle.message("plugin.rules.url.with.msconsumer")
    val expectedInvalidServer = MyBundle.message("plugin.rules.invalid.server", "msdevcrm")

    // Verify both warnings exist in the results
    assertTrue(
      "Should warn about URL with msConsumer",
      warnings.any { it.description == expectedUrlWithMsConsumer },
    )
    assertTrue(
      "Should warn about restricted server",
      warnings.any { it.description == expectedInvalidServer },
    )
  }

  @Test
  fun testInvalidUrlAndPathWithMsConsumer3() {
    // Add expected highlighting information in the test code with updated message format
    myFixture.configureByText(
      "LbInValidUrlModel3.java",
      """
    package com.github.edxref.test;
    
    import com.github.edxref.model.*;
    
    <warning descr="${MyBundle.message("plugin.rules.url.with.msconsumer")}"><warning descr="${MyBundle.message("plugin.rules.invalid.server", "msdevcrm")}">@WSConsumer(url = "http://msdevcrm", path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000, msConsumer = @WSMsConsumer(LbMsType.CRM))</warning></warning>
    public interface LbInValidUrlModel3 extends WebserviceConsumer {
    }
    """
        .trimIndent(),
    )

    myFixture.enableInspections(WSConsumerJavaInspection())
    myFixture.checkHighlighting(true, false, true)
  }

  @Test
  fun testInvalidUrlAndPathWithMsConsume3() {
    myFixture.configureByText(
      "LbInValidUrlModel3.java",
      """
        package com.github.edxref.test;
        
        import com.github.edxref.model.*;
        
        @WSConsumer(url = "http://msdevcrm", path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000, msConsumer = @WSMsConsumer(LbMsType.CRM))
        public interface LbInValidUrlModel3 extends WebserviceConsumer {
        }
    """
        .trimIndent(),
    )

    myFixture.enableInspections(WSConsumerJavaInspection())

    // Don't use checkHighlighting, use doHighlighting and check manually
    val highlights = myFixture.doHighlighting()

    // Verify we have at least 2 warnings
    val warnings = highlights.filter { it.severity.name == "WARNING" }
    assertTrue("Expected at least 2 warnings", warnings.size >= 2)

    // Check for specific warning texts
    val warningTexts = warnings.map { it.description }
    assertTrue(
      "Should warn about URL with msConsumer",
      warningTexts.any { it.contains("'url' must not be specified") },
    )
    assertTrue(
      "Should warn about restricted server",
      warningTexts.any { it.contains("restricted servers") },
    )
  }

  /** Test Case 1: Invalid - URL and path together with msConsumer is not allowed */
  @Test
  fun testInvalidUrlAndPathWithMsConsumer() {
    myFixture.configureByText(
      "LbInValidUrlModel3.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(url = "http://msdevcrm", path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000, msConsumer = @WSMsConsumer(LbMsType.CRM))
            public interface LbInValidUrlModel3 extends WebserviceConsumer {
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case 2: Invalid - Path without msConsumer is not allowed */
  @Test
  fun testInvalidPathWithoutMsConsumer() {
    myFixture.configureByText(
      "LbInValidUrlModel2.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000)
            public interface LbInValidUrlModel2 extends WebserviceConsumer {
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case 3: Valid - Path with CZ msConsumer */
  @Test
  fun testValidPathWithCZMsConsumer() {
    myFixture.configureByText(
      "LbForCZModel1.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000, msConsumer = @WSMsConsumer(LbMsType.CZ))
            public interface LbForCZModel1 extends WebserviceConsumer {
                
                @Property
                @WSParam(isBodyParam = true)
                InputStream getExecutionSuccess();
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case 4: Valid - URL with placeholders */
  @Test
  fun testValidUrlWithPlaceholders() {
    myFixture.configureByText(
      "GetValidModel2.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(url = "http://localhost:8080/webservices/notworking/@userId/@userName/@status", method = WSMethods.GET)
            public interface GetValidModel2 extends WebserviceConsumer {
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case 5: Valid - Path with PEARL msConsumer for PearlWebserviceConsumer */
  @Test
  fun testValidPathWithPearlConsumer() {
    myFixture.configureByText(
      "MsCrmLbPostValidConsumer.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(path = "authtoken/idp/token/create", method = WSMethods.POST, sslCertificateValidation = false, msConsumer = @WSMsConsumer(value = LbMsType.PEARL))
            @HttpRequest(responseType = TokenResponseTest.class)
            public class MsCrmLbPostValidConsumer implements PearlWebserviceConsumer {
            }
            
            // Dummy classes to support the test
            @interface HttpRequest {
                Class<?> responseType();
            }
            class TokenResponseTest {}
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case 6: Invalid - URL with restricted server (msdevcz) */
  @Test
  fun testInvalidUrlWithRestrictedServer() {
    myFixture.configureByText(
      "MsCzLbGetInValid1Consumer.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(url = "http://msdevcz/authtoken/idp/token/validate", method = WSMethods.POST, sslCertificateValidation = false)
            @HttpRequest(responseType = TokenResponseTest.class)
            public class MsCzLbGetInValid1Consumer implements PearlWebserviceConsumer {
            }
            
            // Dummy classes to support the test
            @interface HttpRequest {
                Class<?> responseType();
            }
            class TokenResponseTest {}
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case 7: Invalid - CRM msConsumer for PearlWebserviceConsumer */
  @Test
  fun testInvalidCrmConsumerForPearl() {
    myFixture.configureByText(
      "TestPearlConsumerGetPath.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(path = "mdcontainer/mddata/questionnaire/@questionnaireId/@date", method = WSMethods.GET, sslCertificateValidation = false, msConsumer = @WSMsConsumer(value = LbMsType.CRM))
            @HttpRequest(responseType = Client.class)
            public class TestPearlConsumerGetPath implements PearlWebserviceConsumer {
            }
            
            // Dummy classes to support the test
            @interface HttpRequest {
                Class<?> responseType();
            }
            class Client {}
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case 8: Invalid - PEARL msConsumer for regular WebserviceConsumer */
  @Test
  fun testInvalidPearlConsumerForRegularWS() {
    myFixture.configureByText(
      "LbForCZModel1_Invalid.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(path = "clientpermissions/test/123", method = WSMethods.GET, timeout = 60000, msConsumer = @WSMsConsumer(LbMsType.PEARL))
            public interface LbForCZModel1_Invalid extends WebserviceConsumer {
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case: Missing both URL and path */
  @Test
  fun testMissingUrlAndPath() {
    myFixture.configureByText(
      "MissingUrlAndPath.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(method = WSMethods.GET, timeout = 60000)
            public interface MissingUrlAndPath extends WebserviceConsumer {
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case: Path contains protocol which is invalid */
  @Test
  fun testPathWithProtocol() {
    myFixture.configureByText(
      "PathWithProtocol.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(path = "http://invalid-path/test", method = WSMethods.GET, msConsumer = @WSMsConsumer(LbMsType.CZ))
            public interface PathWithProtocol extends WebserviceConsumer {
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

  /** Test Case: URL with double slashes (invalid) */
  @Test
  fun testUrlWithDoubleSlashes() {
    myFixture.configureByText(
      "UrlWithDoubleSlashes.java",
      """
            package com.github.edxref.test;
            
            import com.github.edxref.model.*;
            
            @WSConsumer(url = "http://localhost:8080//test//path", method = WSMethods.GET)
            public interface UrlWithDoubleSlashes extends WebserviceConsumer {
            }
        """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }
}
