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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.entando.kubernetes.test.e2etest.common.SampleWriter;
import org.entando.kubernetes.test.e2etest.helpers.KeycloakE2ETestHelper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-endd"), @Tag("inter-process"), @Tag("post-deployment")})
class AddEntandoKeycloakServerWithExternalPostgresqlDatabaseIT extends AddEntandoKeycloakServerBaseIT {

    @Test
    void create() {
        helper.externalDatabases().prepareExternalPostgresqlDatabase(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE,
                EntandoKeycloakServer.class.getSimpleName());
        //When I create a EntandoKeycloakServer and I specify it to use PostgreSQL
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder().withNewMetadata()
                .withName(KeycloakE2ETestHelper.KEYCLOAK_NAME)
                .withNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE)
                .endMetadata().withNewSpec()
                .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                .withIngressHostName(KeycloakE2ETestHelper.KEYCLOAK_NAME + "." + helper.getDomainSuffix())
                .withDbms(DbmsVendor.POSTGRESQL)
                .withDefault(true)
                .endSpec().build();
        SampleWriter.writeSample(keycloakServer, "keycloak-with-external-postgresql-db");
        helper.keycloak().createAndWaitForKeycloak(keycloakServer, 0, false);
        //Then I expect to see a valid keycloak deployment
        verifyKeycloakDeployment(keycloakServer, StandardKeycloakImage.KEYCLOAK);
        assertThat(client.apps().deployments().inNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE).withName(
                KeycloakE2ETestHelper.KEYCLOAK_NAME + "-db-deployment")
                .get(), Matchers.is(nullValue()));
        //And recreating the deployment still succeeds because it regenerates all passwords for database schemas
        //Reset all namespaces as they depend on previously created Keycloak clients that are now invalid
        helper.setTextFixture(deleteAll(EntandoKeycloakServer.class)
                .fromNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE));
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_FORCE_DB_PASSWORD_RESET.getJvmSystemProperty(), "true");
        helper.keycloak().createAndWaitForKeycloak(keycloakServer, 0, false);
        verifyKeycloakDeployment(keycloakServer, StandardKeycloakImage.KEYCLOAK);
    }

    @Override
    @AfterEach
    public void afterwards() {
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_FORCE_DB_PASSWORD_RESET.getJvmSystemProperty());
        super.afterwards();

    }
}
