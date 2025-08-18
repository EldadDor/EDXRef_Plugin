package com.github.edxref.inspection

/*
 * User: eadno1
 * Date: 18/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SimpleTest {
  @Test
  fun testBasicAssertion() {
    Assertions.assertEquals(2, 1 + 1, "Basic math should work")
  }
}
