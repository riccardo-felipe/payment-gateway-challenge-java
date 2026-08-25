package com.checkout.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Operational endpoints must not be reachable on the port merchants use.
 *
 * <p>This is a property of the deployment rather than of the framework, which is why it is
 * worth a test: a single line moved in application.yml would put health and metrics on the public
 * port, and nothing else in the build would notice. Anyone who could reach the API could then read
 * the gateway's internals.
 *
 * <p>Both ports are randomised here so the test never collides with a running application or
 * with another test context.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@DisplayName("Actuator exposure")
class ActuatorExposureTest {

  @LocalServerPort
  private int applicationPort;

  @LocalManagementPort
  private int managementPort;

  private RestTestClient application;
  private RestTestClient management;

  @BeforeEach
  void setUp() {
    application = RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + applicationPort)
        .build();

    management = RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + managementPort)
        .build();
  }

  @Test
  @DisplayName("the two ports are not the same")
  void managementRunsOnItsOwnPort() {
    assertThat(managementPort).isNotEqualTo(applicationPort);
  }

  @Test
  @DisplayName("health is not served on the merchant-facing port")
  void healthIsNotOnTheApplicationPort() {
    application.get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  @DisplayName("metrics are not served on the merchant-facing port")
  void metricsAreNotOnTheApplicationPort() {
    application.get()
        .uri("/actuator/metrics")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  @DisplayName("health answers on the management port")
  void healthIsOnTheManagementPort() {
    management.get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP");
  }

  /**
   * Health reports whether to send traffic, nothing more. Component details would describe the
   * gateway's internals to whoever can reach the port.
   */
  @Test
  @DisplayName("health reports no component details")
  void healthHidesItsInternals() {
    management.get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.components").doesNotExist();
  }

  /**
   * Endpoints beyond the three that were opened must stay closed. Actuator's defaults are
   * conservative, but they are defaults - this pins the decision rather than trusting it.
   */
  @Test
  @DisplayName("endpoints that were not opened stay closed")
  void unlistedEndpointsAreNotExposed() {
    management.get()
        .uri("/actuator/env")
        .exchange()
        .expectStatus().isNotFound();

    management.get()
        .uri("/actuator/beans")
        .exchange()
        .expectStatus().isNotFound();
  }
}