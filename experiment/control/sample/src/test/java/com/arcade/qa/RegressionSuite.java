package com.arcade.qa;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Regression suite of the player wallet money flows (see TEST-PLAN.md). The only Surefire entry point, so
 * {@code mvn -B test} runs every autotest exactly once. Classes run sequentially in their {@code @Order}: the
 * service clock and the provider stub are global, so checks must not overlap.
 */
@Suite
@SuiteDisplayName("Player Wallet - money flows regression")
@SelectPackages("com.arcade.qa.tests")
@ConfigurationParameter(key = "junit.jupiter.testclass.order.default",
        value = "org.junit.jupiter.api.ClassOrderer$OrderAnnotation")
@ConfigurationParameter(key = "junit.jupiter.execution.parallel.enabled", value = "false")
public class RegressionSuite {
}
