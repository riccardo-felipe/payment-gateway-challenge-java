package com.checkout.payment.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Rules that the compiler cannot enforce.
 *
 * <p>Deliberately narrow. Generic conventions - controllers ending in Controller, and the
 * like - would be decoration here. Each rule below encodes a mistake that was actually made while
 * building this service, so each one has already paid for itself once.
 */
@AnalyzeClasses(
    packages = "com.checkout.payment.gateway",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /**
   * Dependencies point inwards, and the two outer layers do not know each other.
   *
   * <p>That last part is what put AcquiringBankUnavailableException next to the port rather
   * than beside the adapter that throws it: from infrastructure, the exception handler in
   * interfaces would have had to import infrastructure to catch it.
   */
  @ArchTest
  static final ArchRule layersDependInwards = layeredArchitecture()
      .consideringOnlyDependenciesInLayers()
      .layer("Domain").definedBy("..domain..")
      .layer("Application").definedBy("..application..")
      .layer("Infrastructure").definedBy("..infrastructure..")
      .layer("Interfaces").definedBy("..interfaces..")
      .whereLayer("Interfaces").mayNotBeAccessedByAnyLayer()
      .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
      .whereLayer("Application").mayOnlyBeAccessedByLayers("Interfaces", "Infrastructure")
      .whereLayer("Domain")
      .mayOnlyBeAccessedByLayers("Application", "Interfaces", "Infrastructure");

  /**
   * The domain models payments, not transport or persistence.
   *
   * <p>This rule exists because it was broken: PaymentStatus carried a @JsonValue, putting a
   * serialization concern in the core. It was spotted by eye during review, which is not a process
   * that scales.
   */
  @ArchTest
  static final ArchRule domainIsFreeOfFrameworks = noClasses()
      .that().resideInAPackage("..domain..")
      .should().dependOnClassesThat()
      .resideInAnyPackage(
          "com.fasterxml..",
          "tools.jackson..",
          "org.springframework..",
          "jakarta.validation..",
          "io.github.resilience4j..");

  /**
   * Spring Boot 4 ships Jackson 3 under tools.jackson; only the annotations stayed behind at
   * com.fasterxml.jackson.annotation.
   *
   * <p>The compiler is no help here, because Jackson 2 can sit on the classpath through a
   * transitive dependency: importing the wrong PropertyNamingStrategies compiles cleanly and then
   * silently fails to apply, serialising snake_case fields as camelCase. That happened, and this is
   * the guard against it happening again.
   */
  @ArchTest
  static final ArchRule jacksonComesFromVersionThree = noClasses()
      .should().dependOnClassesThat()
      .resideInAnyPackage("com.fasterxml.jackson.databind..", "com.fasterxml.jackson.core..");
}