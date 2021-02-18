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

import static org.entando.kubernetes.model.DbmsVendor.POSTGRESQL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.entando.kubernetes.controller.spi.common.DbmsDockerVendorStrategy;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.e2etest.common.SampleWriter;
import org.entando.kubernetes.test.e2etest.helpers.KeycloakE2ETestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("smoke-test"), @Tag("post-deployment")})
class AddEntandoKeycloakServerWithContainerizedDatabaseIT extends AddEntandoKeycloakServerBaseIT implements FluentTraversals {

    @AfterEach
    void resetComplianceMode() {
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty());
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_IMAGE_PULL_SECRETS.getJvmSystemProperty());
    }

    @ParameterizedTest
    @EnumSource(value = EntandoOperatorComplianceMode.class, names = {"COMMUNITY", "REDHAT"})
    void create(EntandoOperatorComplianceMode complianceMode) {
        //Given the operator runs in a specified complianceMode
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty(),
                complianceMode.name().toLowerCase());
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_IMAGE_PULL_SECRETS.getJvmSystemProperty(), "redhat-registry");
        //When I create a EntandoKeycloakServer and I specify it to use PostgreSQL
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder().withNewMetadata()
                .withName(KeycloakE2ETestHelper.KEYCLOAK_NAME)
                .withNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE)
                .endMetadata().withNewSpec()
                .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                .withIngressHostName(KeycloakE2ETestHelper.KEYCLOAK_NAME + "." + helper.getDomainSuffix())
                .withDbms(POSTGRESQL)
                .withDefault(true)
                .endSpec().build();
        SampleWriter.writeSample(keycloakServer, "keycloak-with-embedded-postgresql-db");
        helper.keycloak().createAndWaitForKeycloak(keycloakServer, 30, true);
        //Then I expect to see
        verifyKeycloakDatabaseDeployment(complianceMode);
        StandardKeycloakImage standardServerImage;
        if (complianceMode == EntandoOperatorComplianceMode.COMMUNITY) {
            standardServerImage = StandardKeycloakImage.KEYCLOAK;
        } else {
            standardServerImage = StandardKeycloakImage.REDHAT_SSO;
        }
        verifyKeycloakDeployment(keycloakServer, standardServerImage);
    }

    private void verifyKeycloakDatabaseDeployment(EntandoOperatorComplianceMode complianceMode) {
        Deployment deployment = client.apps().deployments()
                .inNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE)
                .withName(KeycloakE2ETestHelper.KEYCLOAK_NAME + "-db-deployment")
                .get();
        assertThat(thePortNamed(DB_PORT).on(theContainerNamed("db-container").on(deployment))
                .getContainerPort(), equalTo(KEYCLOAK_DB_PORT));
        DbmsDockerVendorStrategy dockerVendorStrategy = null;
        if (complianceMode == EntandoOperatorComplianceMode.COMMUNITY) {
            dockerVendorStrategy = DbmsDockerVendorStrategy.CENTOS_POSTGRESQL;
        } else {
            dockerVendorStrategy = DbmsDockerVendorStrategy.RHEL_POSTGRESQL;
        }
        assertThat(theContainerNamed("db-container").on(deployment).getImage(), containsString(dockerVendorStrategy.getRegistry()));
        assertThat(theContainerNamed("db-container").on(deployment).getImage(), containsString(dockerVendorStrategy.getImageRepository()));
        assertThat(theContainerNamed("db-container").on(deployment).getImage(), containsString(dockerVendorStrategy.getOrganization()));
        Service service = client.services().inNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE).withName(
                KeycloakE2ETestHelper.KEYCLOAK_NAME + "-db-service").get();
        assertThat(thePortNamed(DB_PORT).on(service).getPort(), equalTo(KEYCLOAK_DB_PORT));
        assertThat(deployment.getStatus().getReadyReplicas(), greaterThanOrEqualTo(1));
        assertThat("It has a db status", helper.keycloak().getOperations()
                .inNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE).withName(KeycloakE2ETestHelper.KEYCLOAK_NAME)
                .fromServer().get().getStatus().forDbQualifiedBy("db").isPresent());
    }
}
