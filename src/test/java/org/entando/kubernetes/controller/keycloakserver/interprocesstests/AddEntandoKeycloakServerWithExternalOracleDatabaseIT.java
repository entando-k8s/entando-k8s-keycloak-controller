/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.keycloakserver.interprocesstests;

import static org.entando.kubernetes.controller.KubeUtils.snakeCaseOf;
import static org.entando.kubernetes.model.DbmsVendor.ORACLE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("oracle-end-to-end"), @Tag("inter-process")})
class AddEntandoKeycloakServerWithExternalOracleDatabaseIT extends AddEntandoKeycloakServerBaseIT {

    @Test
    void create() {
        helper.externalDatabases()
                .prepareExternalOracleDatabase(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE,
                        snakeCaseOf(KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "-db"));
        //When I create a EntandoKeycloakServer and I specify it to use PostgreSQL
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder().withNewMetadata()
                .withName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME)
                .withNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE)
                .endMetadata().withNewSpec()
                .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                .withIngressHostName(KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "." + helper.getDomainSuffix())
                .withDbms(ORACLE)
                .withDefault(true)
                .endSpec().build();
        SampleWriter.writeSample(keycloakServer, "keycloak-with-external-oracle-db");
        helper.keycloak().createAndWaitForKeycloak(keycloakServer, 0, false);
        //Then I expect to see
        verifyKeycloakDeployment(keycloakServer, StandardKeycloakImage.KEYCLOAK);
        assertThat(
                client.apps().deployments().inNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE).withName(
                        KeycloakIntegrationTestHelper.KEYCLOAK_NAME + "-db-deployment")
                        .get(), is(nullValue()));
    }
}
