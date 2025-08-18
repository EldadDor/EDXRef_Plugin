package com.github.edxref.inspection

import com.github.edxref.MyBundle
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo

class WSConsumerJavaInspectionKotlinTest : LightJavaCodeInsightFixtureTestCase() {

  override fun getProjectDescriptor(): LightProjectDescriptor {
    return object : DefaultLightProjectDescriptor() {
      override fun configureModule(
        module: Module,
        model: ModifiableRootModel,
        contentEntry: ContentEntry,
      ) {
        super.configureModule(module, model, contentEntry)
        // Configure module for K2 compatibility
      }
    }
  }

  override fun getTestDataPath(): String {
    return "src/test/testData"
  }

  @BeforeEach
  fun setUpTest(testInfo: TestInfo) {
    // Set up K2 and test-specific properties
    System.setProperty("kotlin.script.disable.auto.import", "true")
    System.setProperty("kotlin.script.disable.compilation", "true")
    System.setProperty("idea.force.use.core.classloader.for.plugin.path", "true")
    System.setProperty("idea.kotlin.plugin.use.k2", "true")
  }

  override fun setUp() {
    super.setUp()
    // Create model classes for tests
    createModelClasses()

    // Enable the inspection
    myFixture.enableInspections(WSConsumerJavaInspection::class.java)
  }

  private fun createModelClasses() {
    // Create all necessary model classes for testing
    myFixture.configureByText(
      "WSConsumer.java",
      """
      package com.github.edxref.model;
      public @interface WSConsumer {
        String url() default "";
        String path() default "";
        WSMethods method() default WSMethods.GET;
        int timeout() default 30000;
        WSMsConsumer msConsumer() default @WSMsConsumer(LbMsType.LOCAL);
        boolean sslCertificateValidation() default true;
      }
    """
        .trimIndent(),
    )

    myFixture.configureByText(
      "WSMethods.java",
      """
      package com.github.edxref.model;
      public enum WSMethods { GET, POST, PUT, DELETE }
    """
        .trimIndent(),
    )

    myFixture.configureByText(
      "WSMsConsumer.java",
      """
      package com.github.edxref.model;
      public @interface WSMsConsumer {
        LbMsType value() default LbMsType.LOCAL;
      }
    """
        .trimIndent(),
    )

    myFixture.configureByText(
      "LbMsType.java",
      """
      package com.github.edxref.model;
      public enum LbMsType { LOCAL, CRM, CZ, PEARL }
    """
        .trimIndent(),
    )

    myFixture.configureByText(
      "WebserviceConsumer.java",
      """
      package com.github.edxref.model;
      public interface WebserviceConsumer {}
    """
        .trimIndent(),
    )

    myFixture.configureByText(
      "PearlWebserviceConsumer.java",
      """
      package com.github.edxref.model;
      public interface PearlWebserviceConsumer {}
    """
        .trimIndent(),
    )

    myFixture.configureByText(
      "Property.java",
      """
      package com.github.edxref.model;
      public @interface Property {}
    """
        .trimIndent(),
    )

    myFixture.configureByText(
      "WSParam.java",
      """
      package com.github.edxref.model;
      public @interface WSParam {
        boolean isBodyParam() default false;
        String name() default "";
      }
    """
        .trimIndent(),
    )
  }

  @Test
  fun testInvalidUrlAndPathWithMsConsumer2() {
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

    // Get highlighting results
    val highlights = myFixture.doHighlighting()
    val warnings = highlights.filter { it.severity.name == "WARNING" }

    // Get expected messages from MyBundle
    val expectedUrlWithMsConsumer = MyBundle.message("plugin.rules.url.with.msconsumer")
    val expectedInvalidServer = MyBundle.message("plugin.rules.invalid.server", "msdevcrm")

    // Verify both warnings exist using JUnit 5 assertions
    Assertions.assertTrue(
      warnings.any { it.description == expectedUrlWithMsConsumer },
      "Should warn about URL with msConsumer",
    )
    Assertions.assertTrue(
      warnings.any { it.description == expectedInvalidServer },
      "Should warn about restricted server",
    )
  }

  @Test
  fun testInvalidUrlAndPathWithMsConsumer3() {
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

    val highlights = myFixture.doHighlighting()
    val warnings = highlights.filter { it.severity.name == "WARNING" }

    Assertions.assertTrue(warnings.size >= 2, "Expected at least 2 warnings")

    val warningTexts = warnings.map { it.description }
    Assertions.assertTrue(
      warningTexts.any { it.contains("'url' must not be specified") },
      "Should warn about URL with msConsumer",
    )
    Assertions.assertTrue(
      warningTexts.any { it.contains("restricted servers") },
      "Should warn about restricted server",
    )
  }

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

  @Test
  fun testValidPathWithCZMsConsumer() {
    myFixture.configureByText(
      "LbForCZModel1.java",
      """
        package com.github.edxref.test;
        
        import com.github.edxref.model.*;
        import java.io.InputStream;
        
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
        
        @interface HttpRequest {
            Class<?> responseType();
        }
        
        class TokenResponseTest {}
      """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

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
        
        @interface HttpRequest {
            Class<?> responseType();
        }
        
        class TokenResponseTest {}
      """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

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
        
        @interface HttpRequest {
            Class<?> responseType();
        }
        
        class Client {}
      """
        .trimIndent(),
    )

    myFixture.checkHighlighting(true, false, true)
  }

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
