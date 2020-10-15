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

package org.entando.kubernetes.controller.keycloakserver;

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import java.util.Optional;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.DeployCommand;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.ExposedService;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.common.KeycloakConnectionSecret;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;

public class EntandoKeycloakServerController extends AbstractDbAwareController<EntandoKeycloakServer> {

    @Inject
    public EntandoKeycloakServerController(KubernetesClient kubernetesClient) {
        super(kubernetesClient);
    }

    public EntandoKeycloakServerController(KubernetesClient kubernetesClient, boolean exitAutomatically) {
        super(kubernetesClient, exitAutomatically);
    }

    public EntandoKeycloakServerController(SimpleK8SClient<?> k8sClient, SimpleKeycloakClient keycloakClient) {
        super(k8sClient, keycloakClient);
    }

    public void onStartup(@Observes StartupEvent event) {
        processCommand();
    }

    @Override
    protected void synchronizeDeploymentState(EntandoKeycloakServer newEntandoKeycloakServer) {
        DatabaseServiceResult databaseServiceResult = prepareKeycloakDatabaseService(newEntandoKeycloakServer);
        Secret existingKeycloakAdminSecret = prepareLocalKeycloakAdminSecret(newEntandoKeycloakServer);
        KeycloakServiceDeploymentResult serviceDeploymentResult = deployKeycloak(newEntandoKeycloakServer, databaseServiceResult,
                existingKeycloakAdminSecret);
        ensureHttpAccess(serviceDeploymentResult);
        Secret myLocalKeycloakAdminSecret = overwriteMyKeycloakAdminSecret(newEntandoKeycloakServer, serviceDeploymentResult);
        if (newEntandoKeycloakServer.getSpec().isDefault()) {
            overwriteDefaultKeycloakAdminSecret(serviceDeploymentResult);
        }
        ensureKeycloakRealm(new KeycloakConnectionSecret(myLocalKeycloakAdminSecret));
        k8sClient.entandoResources().updateStatus(newEntandoKeycloakServer, serviceDeploymentResult.getStatus());
    }

    private void overwriteDefaultKeycloakAdminSecret(KeycloakServiceDeploymentResult serviceDeploymentResult) {
        //Create or overwrite the default local keycloak admin secret in case new credentials were created.
        overwriteKeycloakSecret(
                serviceDeploymentResult,
                EntandoOperatorConfig.getDefaultKeycloakSecretName(),
                new KeycloakConnectionSecret(serviceDeploymentResult.getKeycloakAdminSecret()));
    }

    private Secret overwriteMyKeycloakAdminSecret(EntandoKeycloakServer newEntandoKeycloakServer,
            KeycloakServiceDeploymentResult serviceDeploymentResult) {
        //Create or overwrite the local keycloak admin secret for this EntandoKeycloakServer in case new credentials were created.
        return overwriteKeycloakSecret(
                serviceDeploymentResult,
                myLocalKeycloakAdminSecretName(newEntandoKeycloakServer),
                new KeycloakConnectionSecret(serviceDeploymentResult.getKeycloakAdminSecret()));
    }

    private void ensureHttpAccess(KeycloakServiceDeploymentResult serviceDeploymentResult) {
        //Give the operator access over http for cluster.local calls
        k8sClient.pods().executeOnPod(serviceDeploymentResult.getPod(), "server-container",
                "cd /opt/jboss/keycloak/bin",
                "./kcadm.sh config credentials --server http://localhost:8080/auth --realm master --user $KEYCLOAK_USER --password "
                        + "$KEYCLOAK_PASSWORD",
                "./kcadm.sh update realms/master -s sslRequired=NONE"
        );
    }

    private KeycloakServiceDeploymentResult deployKeycloak(EntandoKeycloakServer newEntandoKeycloakServer,
            DatabaseServiceResult databaseServiceResult, Secret existingKeycloakAdminSecret) {
        // Create the Keycloak service using the provided database and  the locally stored keycloak admin credentials
        // for this EntandoKeycloakServer.
        KeycloakDeployable keycloakDeployable = new KeycloakDeployable(
                newEntandoKeycloakServer,
                databaseServiceResult,
                existingKeycloakAdminSecret);
        DeployCommand<KeycloakServiceDeploymentResult, EntandoKeycloakServer> keycloakCommand = new DeployCommand<>(keycloakDeployable);
        return keycloakCommand.execute(k8sClient, Optional.of(keycloakClient)).withStatus(keycloakCommand.getStatus());
    }

    private Secret prepareLocalKeycloakAdminSecret(EntandoKeycloakServer newEntandoKeycloakServer) {
        Secret existingKeycloakAdminSecret = k8sClient.secrets()
                .loadControllerSecret(myLocalKeycloakAdminSecretName(newEntandoKeycloakServer));
        if (existingKeycloakAdminSecret == null) {
            //We need to FIRST populate the secret in the controller's namespace so that, if deployment fails, we have the credentials
            // that the secret in the Deployment's namespace was based on, because we may not have read access to it.
            k8sClient.secrets().overwriteControllerSecret(new SecretBuilder()
                    .withNewMetadata()
                    .withName(myLocalKeycloakAdminSecretName(newEntandoKeycloakServer))
                    .endMetadata()
                    .addToStringData(KubeUtils.USERNAME_KEY, "entando_keycloak_admin")
                    .addToStringData(KubeUtils.PASSSWORD_KEY, RandomStringUtils.randomAlphanumeric(10))
                    .build());

        }
        return existingKeycloakAdminSecret;
    }

    private DatabaseServiceResult prepareKeycloakDatabaseService(EntandoKeycloakServer newEntandoKeycloakServer) {
        // Create database for Keycloak
        return prepareDatabaseService(
                newEntandoKeycloakServer,
                newEntandoKeycloakServer.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED),
                "db");
    }

    private String myLocalKeycloakAdminSecretName(EntandoKeycloakServer newEntandoKeycloakServer) {
        return newEntandoKeycloakServer.getMetadata().getName() + "-connection-secret";
    }

    private Secret overwriteKeycloakSecret(ExposedService serviceDeploymentResult, String secretName,
            KeycloakConnectionConfig keycloakConnectionConfig) {
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .endMetadata()
                .addToStringData(KubeUtils.USERNAME_KEY, keycloakConnectionConfig.getUsername())
                .addToStringData(KubeUtils.PASSSWORD_KEY, keycloakConnectionConfig.getPassword())
                .addToStringData(KubeUtils.URL_KEY, serviceDeploymentResult.getExternalBaseUrl())
                .addToStringData(KubeUtils.INTERNAL_URL_KEY, serviceDeploymentResult.getInternalBaseUrl())
                .build();
        k8sClient.secrets().overwriteControllerSecret(secret);
        return secret;
    }

    private void ensureKeycloakRealm(KeycloakConnectionConfig keycloakConnectionConfig) {
        logger.severe(() -> format("Attempting to log into Keycloak at %s", keycloakConnectionConfig.determineBaseUrl()));
        keycloakClient.login(keycloakConnectionConfig.determineBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        keycloakClient.ensureRealm(KubeUtils.ENTANDO_KEYCLOAK_REALM);
    }

}
