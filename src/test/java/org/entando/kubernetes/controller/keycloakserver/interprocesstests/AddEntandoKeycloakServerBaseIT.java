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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.keycloakserver.EntandoKeycloakServerController;
import org.entando.kubernetes.controller.spi.capability.impl.DefaultCapabilityClient;
import org.entando.kubernetes.controller.spi.client.impl.DefaultKubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.common.TrustStoreHelper;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.support.client.impl.DefaultKeycloakClient;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.controller.support.command.InProcessCommandStream;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.test.e2etest.helpers.EntandoAppE2ETestHelper;
import org.entando.kubernetes.test.e2etest.helpers.EntandoPluginE2ETestHelper;
import org.entando.kubernetes.test.e2etest.helpers.K8SIntegrationTestHelper;
import org.entando.kubernetes.test.e2etest.helpers.KeycloakE2ETestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class AddEntandoKeycloakServerBaseIT implements FluentIntegrationTesting {

    public static final int KEYCLOAK_DB_PORT = 5432;
    protected K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();
    protected KubernetesClient client;

    protected AddEntandoKeycloakServerBaseIT() {
        EntandoOperatorSpiConfig.getCertificateAuthoritySecretName()
                .ifPresent(s -> TrustStoreHelper.trustCertificateAuthoritiesIn(helper.getClient().secrets().withName(s).get()));
    }

    @BeforeEach
    public void cleanup() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                TestFixturePreparation.ENTANDO_CONTROLLERS_NAMESPACE);
        client = helper.getClient();
        //Reset all namespaces as they depend on previously created Keycloak clients that are now invalid
        helper.setTextFixture(deleteAll(EntandoKeycloakServer.class)
                .fromNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE)
                .deleteAll(EntandoDatabaseService.class)
                .fromNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE)
                .deleteAll(EntandoApp.class)
                .fromNamespace(EntandoAppE2ETestHelper.TEST_NAMESPACE)
                .deleteAll(EntandoPlugin.class)
                .fromNamespace(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE)
        );
        helper.externalDatabases().deletePgTestPod(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE);
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            helper.keycloak().listenAndRespondWithImageVersionUnderTest(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE);
        } else {
            EntandoKeycloakServerController controller = new EntandoKeycloakServerController(
                    new DefaultKubernetesClientForControllers(helper.getClient()),
                    new DefaultCapabilityClient(helper.getClient()),
                    new InProcessCommandStream(new DefaultSimpleK8SClient(helper.getClient()), new DefaultKeycloakClient()),
                    new DefaultKeycloakClient()
            );
            helper.keycloak().listenAndRun(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE, controller);
        }
    }

    @AfterEach
    public void afterwards() {
        helper.afterTest();
        client.close();
    }

    protected void verifyKeycloakDeployment(EntandoKeycloakServer entandoKeycloakServer, StandardKeycloakImage standardKeycloakImage) {
        String http = HttpTestHelper.getDefaultProtocol();
        await().atMost(15, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).ignoreExceptions().until(() -> HttpTestHelper
                .statusOk(http + "://" + KeycloakE2ETestHelper.KEYCLOAK_NAME + "." + helper.getDomainSuffix()
                        + "/auth"));
        Deployment deployment = client.apps().deployments().inNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE)
                .withName(KeycloakE2ETestHelper.KEYCLOAK_NAME + "-server-deployment").get();
        assertThat(thePortNamed("server-port")
                        .on(theContainerNamed("server-container").on(deployment))
                        .getContainerPort(),
                is(8080));
        assertThat(theContainerNamed("server-container").on(deployment).getImage(),
                containsString(standardKeycloakImage.name().toLowerCase().replace("_", "-")));
        Service service = client.services().inNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE).withName(
                KeycloakE2ETestHelper.KEYCLOAK_NAME + "-server-service").get();
        assertThat(thePortNamed("server-port").on(service).getPort(), is(8080));
        assertTrue(deployment.getStatus().getReadyReplicas() >= 1);
        assertTrue(helper.keycloak().getOperations()
                .inNamespace(KeycloakE2ETestHelper.KEYCLOAK_NAMESPACE).withName(KeycloakE2ETestHelper.KEYCLOAK_NAME)
                .fromServer().get().getStatus().forServerQualifiedBy("server").isPresent());

        Secret adminSecret = client.secrets()
                .inNamespace(client.getNamespace())
                .withName(KeycloakName.forTheAdminSecret(entandoKeycloakServer))
                .get();
        assertNotNull(adminSecret);
        assertTrue(adminSecret.getData().containsKey(SecretUtils.USERNAME_KEY));
        assertTrue(adminSecret.getData().containsKey(SecretUtils.PASSSWORD_KEY));
        ConfigMap configMap = client.configMaps()
                .inNamespace(client.getNamespace())
                .withName(KeycloakName.forTheConnectionConfigMap(entandoKeycloakServer))
                .get();
        assertNotNull(configMap);
        assertTrue(configMap.getData().containsKey(NameUtils.URL_KEY));
    }
}
