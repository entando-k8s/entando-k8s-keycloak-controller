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

package org.entando.kubernetes.controller.keycloakserver.inprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.LabeledArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.keycloakserver.EntandoKeycloakServerController;
import org.entando.kubernetes.controller.spi.container.KeycloakClientConfig;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.test.support.CommonLabels;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.keycloakserver.DoneableEntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
//in execute component test
@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
//Because SONAR doesn't recognize custom matchers and captors
@SuppressWarnings({"java:S6068", "java:S6073"})
class DeployKeycloakWithContainerizedDatabaseTest implements InProcessTestUtil, FluentTraversals, CommonLabels {

    static final String MY_KEYCLOAK_SERVER_DEPLOYMENT = MY_KEYCLOAK + "-server-deployment";
    private static final String MY_KEYCLOAK_DB_SECRET = MY_KEYCLOAK + "-db-secret";
    private final EntandoKeycloakServer keycloakServer = new DoneableEntandoKeycloakServer(newEntandoKeycloakServer(), s -> s)
            .editSpec().withDbms(DbmsVendor.NONE).endSpec()
            .done();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
    private EntandoKeycloakServerController keycloakServerController;

    @BeforeEach
    void prepare() {
        client.entandoResources().createOrPatchEntandoResource(keycloakServer);
        keycloakServerController = new EntandoKeycloakServerController(client, keycloakClient);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, keycloakServer.getMetadata().getName());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, keycloakServer.getMetadata().getNamespace());

    }

    @Test
    void testSecrets() {
        //When I deploy a EntandoKeycloakServer
        keycloakServerController.onStartup(new StartupEvent());
        //Then no secrets were created for the Database
        NamedArgumentCaptor<Secret> keycloakSecretCaptor = forResourceNamed(Secret.class, MY_KEYCLOAK_DB_SECRET);
        verify(client.secrets(), never()).createSecretIfAbsent(eq(keycloakServer), keycloakSecretCaptor.capture());
    }

    @Test
    void testDeployment() {
        //And Keycloak is receiving requests
        lenient().when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);
        //When I deploy a EntandoKeycloakServer
        keycloakServerController.onStartup(new StartupEvent());

        //Then a K8S deployment is created
        NamedArgumentCaptor<Deployment> keycloakDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_KEYCLOAK_SERVER_DEPLOYMENT);
        verify(client.deployments()).createOrPatchDeployment(eq(keycloakServer), keycloakDeploymentCaptor.capture());
        Deployment deployment = keycloakDeploymentCaptor.getValue();
        //Then no database schema preparation job was invoked
        LabeledArgumentCaptor<Pod> keycloakSchemaJobCaptor = forResourceWithLabels(Pod.class,
                dbPreparationJobLabels(keycloakServer, "server"));
        verify(client.pods(), never()).runToCompletion(keycloakSchemaJobCaptor.capture());
        //And the DB_VENDOR is set to h2
        assertThat(theVariableNamed("DB_VENDOR").on(theContainerNamed("server-container").on(deployment)), is("h2"));
        //verify that a persistent volume claim was created for the h2 database:
        assertNotNull(this.client.persistentVolumeClaims().loadPersistentVolumeClaim(keycloakServer, MY_KEYCLOAK + "-server-pvc"));
        assertThat(theVolumeNamed(MY_KEYCLOAK + "-server-volume").on(deployment).getPersistentVolumeClaim().getClaimName(),
                is(MY_KEYCLOAK + "-server-pvc"));
        assertThat(
                theVolumeMountNamed(MY_KEYCLOAK + "-server-volume").on(theContainerNamed("server-container").on(deployment)).getMountPath(),
                is("/opt/jboss/keycloak/standalone/data"));
        verifyThatAllVolumesAreMapped(keycloakServer, client, deployment);
    }

}
