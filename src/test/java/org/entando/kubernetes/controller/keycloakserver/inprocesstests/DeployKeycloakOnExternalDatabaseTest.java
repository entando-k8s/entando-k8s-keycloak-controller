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

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.common.CreateExternalServiceCommand;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.LabeledArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.keycloakserver.EntandoKeycloakServerController;
import org.entando.kubernetes.controller.test.support.CommonLabels;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseServiceBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
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
class DeployKeycloakOnExternalDatabaseTest implements InProcessTestUtil, FluentTraversals, CommonLabels {

    static final String MY_KEYCLOAK_SERVER_DEPLOYMENT = MY_KEYCLOAK + "-server-deployment";
    private static final String MY_KEYCLOAK_DB_SECRET = MY_KEYCLOAK + "-db-secret";
    private final EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder(newEntandoKeycloakServer()).editSpec()
            .withDbms(DbmsVendor.ORACLE)
            .endSpec().build();
    private final EntandoDatabaseService externalDatabase = buildEntandoDatabaseService();
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
    private EntandoKeycloakServerController keycloakServerController;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setEntandoDatabaseServiceNamespace() {
        externalDatabase.getMetadata().setNamespace(keycloakServer.getMetadata().getNamespace());
        client.entandoResources().createOrPatchEntandoResource(externalDatabase);
        client.entandoResources().createOrPatchEntandoResource(keycloakServer);
        keycloakServerController = new EntandoKeycloakServerController((SimpleK8SClient) client, keycloakClient);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, keycloakServer.getMetadata().getName());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, keycloakServer.getMetadata().getNamespace());

    }

    @Test
    void testSecrets() {
        //Given I have created an EntandoDatabaseService custom resource
        new CreateExternalServiceCommand(externalDatabase).execute(client);
        //When I deploy a EntandoKeycloakServer
        keycloakServerController.onStartup(new StartupEvent());
        //Then a K8S Secret was created with a name that reflects the EntandoApp and the fact that it is a secret
        NamedArgumentCaptor<Secret> keycloakSecretCaptor = forResourceNamed(Secret.class, MY_KEYCLOAK_DB_SECRET);
        verify(client.secrets()).createSecretIfAbsent(eq(keycloakServer), keycloakSecretCaptor.capture());
        Secret keycloakSecret = keycloakSecretCaptor.getValue();
        assertThat(keycloakSecret.getStringData().get(KubeUtils.USERNAME_KEY), is("my_keycloak_db"));
        assertThat(keycloakSecret.getStringData().get(KubeUtils.PASSSWORD_KEY), is(not(emptyOrNullString())));
    }

    @Test
    void testDeployment() {
        //Given I have created an EntandoDatabaseService custom resource
        new CreateExternalServiceCommand(externalDatabase).execute(client);
        //And Keycloak is receiving requests
        lenient().when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);
        //When I deploy a EntandoKeycloakServer
        keycloakServerController.onStartup(new StartupEvent());

        //Then a K8S deployment is created
        NamedArgumentCaptor<Deployment> keyclaokDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_KEYCLOAK_SERVER_DEPLOYMENT);
        verify(client.deployments()).createOrPatchDeployment(eq(keycloakServer), keyclaokDeploymentCaptor.capture());
        //Then a pod was created for Keycloak using the credentials and connection settings of the EntandoDatabaseService
        LabeledArgumentCaptor<Pod> keycloakSchemaJobCaptor = forResourceWithLabels(Pod.class,
                dbPreparationJobLabels(keycloakServer, "server"));
        verify(client.pods()).runToCompletion(keycloakSchemaJobCaptor.capture());
        Pod keycloakDbJob = keycloakSchemaJobCaptor.getValue();
        Container theInitContainer = theInitContainerNamed(MY_KEYCLOAK + "-db-schema-creation-job").on(keycloakDbJob);
        verifyStandardSchemaCreationVariables("my-secret", MY_KEYCLOAK_DB_SECRET, theInitContainer, DbmsVendor.ORACLE);
        assertThat(theVariableNamed(DATABASE_SERVER_HOST).on(theInitContainer),
                is("mydb-db-service." + MY_KEYCLOAK_NAMESPACE + ".svc.cluster.local"));
        //And it was instructed to create a schema reflecting the keycloakdb user
        assertThat(theVariableNamed(DATABASE_NAME).on(theInitContainer), is("my_db"));
    }

    private EntandoDatabaseService buildEntandoDatabaseService() {
        return new EntandoDatabaseServiceBuilder()
                .withNewMetadata()
                .withName("mydb")
                .withNamespace("mynamespace")
                .endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.ORACLE)
                .withHost("myoracle.com")
                .withPort(1521)
                .withDatabaseName("my_db")
                .withSecretName("my-secret")
                .withCreateDeployment(false).endSpec().build();
    }
}
