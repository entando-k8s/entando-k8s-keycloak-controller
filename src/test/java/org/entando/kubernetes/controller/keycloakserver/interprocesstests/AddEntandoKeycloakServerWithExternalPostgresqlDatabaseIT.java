package org.entando.kubernetes.controller.keycloakserver.interprocesstests;

import static org.entando.kubernetes.model.DbmsVendor.POSTGRESQL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process")})
public class AddEntandoKeycloakServerWithExternalPostgresqlDatabaseIT extends AddEntandoKeycloakServerBaseIT {

    @Test
    public void create() {
        helper.externalDatabases().prepareExternalPostgresqlDatabase(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE,
                EntandoKeycloakServer.class.getSimpleName());
        //When I create a EntandoKeycloakServer and I specify it to use PostgreSQL
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder().withNewMetadata()
                .withName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME)
                .withNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE)
                .endMetadata().withNewSpec()
                .withImageName("entando/entando-keycloak")
                .withIngressHostName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "." + helper.getDomainSuffix())
                .withDbms(POSTGRESQL)
                .withDefault(true)
                .endSpec().build();
        SampleWriter.writeSample(keycloakServer, "keycloak-with-external-postgresql-db");
        helper.keycloak().createAndWaitForKeycloak(keycloakServer, 0, false);
        //Then I expect to see
        verifyKeycloakDeployment();
        assertThat(client.apps().deployments().inNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE).withName(
                KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "-db-deployment")
                .get(), Matchers.is(nullValue()));
    }
}
