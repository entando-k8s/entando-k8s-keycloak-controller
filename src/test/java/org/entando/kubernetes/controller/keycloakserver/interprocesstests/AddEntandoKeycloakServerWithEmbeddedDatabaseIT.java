package org.entando.kubernetes.controller.keycloakserver.interprocesstests;

import static org.entando.kubernetes.model.DbmsVendor.POSTGRESQL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("smoke-test")})
public class AddEntandoKeycloakServerWithEmbeddedDatabaseIT extends AddEntandoKeycloakServerBaseIT implements FluentTraversals {

    @Test
    public void create() {
        //When I create a EntandoKeycloakServer and I specify it to use PostgreSQL
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder().withNewMetadata()
                .withName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME)
                .withNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE)
                .endMetadata().withNewSpec()
                .withImageName("entando/entando-keycloak")
                .withIngressHostName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "." + helper.getDomainSuffix())
                .withDbms(POSTGRESQL)
                .withEntandoImageVersion("6.0.5")
                .withDefault(true)
                .endSpec().build();
        SampleWriter.writeSample(keycloakServer, "keycloak-with-embedded-postgresql-db");
        helper.keycloak().createAndWaitForKeycloak(keycloakServer, 30, true);
        //Then I expect to see
        verifyKeycloakDatabaseDeployment();
        verifyKeycloakDeployment();
    }

    private void verifyKeycloakDatabaseDeployment() {
        Deployment deployment = client.apps().deployments()
                .inNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE)
                .withName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "-db-deployment")
                .get();
        assertThat(thePortNamed(DB_PORT).on(theContainerNamed("db-container").on(deployment))
                .getContainerPort(), equalTo(KEYCLOAK_DB_PORT));
        Service service = client.services().inNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE).withName(
                KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "-db-service").get();
        assertThat(thePortNamed(DB_PORT).on(service).getPort(), equalTo(KEYCLOAK_DB_PORT));
        assertThat(deployment.getStatus().getReadyReplicas(), greaterThanOrEqualTo(1));
        assertThat("It has a db status", helper.keycloak().getOperations()
                .inNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE).withName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME)
                .fromServer().get().getStatus().forDbQualifiedBy("db").isPresent());
    }
}
