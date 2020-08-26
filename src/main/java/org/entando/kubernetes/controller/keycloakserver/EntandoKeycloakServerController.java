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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import java.util.Optional;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.DeployCommand;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
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
        // Create database for Keycloak
        DatabaseServiceResult databaseServiceResult = prepareDatabaseService(newEntandoKeycloakServer,
                newEntandoKeycloakServer.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED), "db");
        // Create the Keycloak service using the provided database
        KeycloakDeployable keycloakDeployable = new KeycloakDeployable(newEntandoKeycloakServer, databaseServiceResult);
        DeployCommand<ServiceDeploymentResult> keycloakCommand = new DeployCommand<>(keycloakDeployable);
        ServiceDeploymentResult serviceDeploymentResult = keycloakCommand.execute(k8sClient, Optional.of(keycloakClient));
        if (keycloakCommand.getPod() != null) {
            String secretName = EntandoOperatorConfig.getDefaultKeycloakSecretName();
            Secret kcSecret = k8sClient.secrets().loadSecret(
                    newEntandoKeycloakServer, KeycloakDeployableContainer.secretName(newEntandoKeycloakServer));
            KeycloakConnectionConfig keycloakConnectionConfig = new KeycloakConnectionSecret(kcSecret);
            overwriteKeycloakSecret(serviceDeploymentResult, newEntandoKeycloakServer.getMetadata().getName() + "-connection-secret",
                    keycloakConnectionConfig);
            if (newEntandoKeycloakServer.getSpec().isDefault()) {
                overwriteKeycloakSecret(serviceDeploymentResult, secretName, keycloakConnectionConfig);
            }
            ensureKeycloakRealm(keycloakConnectionConfig);
        }
        k8sClient.entandoResources().updateStatus(newEntandoKeycloakServer, keycloakCommand.getStatus());
    }

    private void overwriteKeycloakSecret(ServiceDeploymentResult serviceDeploymentResult, String secretName,
            KeycloakConnectionConfig keycloakConnectionConfig) {
        k8sClient.secrets().overwriteControllerSecret(new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .endMetadata()
                .addToStringData(KubeUtils.USERNAME_KEY, keycloakConnectionConfig.getUsername())
                .addToStringData(KubeUtils.PASSSWORD_KEY, keycloakConnectionConfig.getPassword())
                .addToStringData(KubeUtils.URL_KEY, serviceDeploymentResult.getExternalBaseUrl())
                .addToStringData(KubeUtils.INTERNAL_URL_KEY, serviceDeploymentResult.getInternalBaseUrl())
                .build());
    }

    private void ensureKeycloakRealm(KeycloakConnectionConfig keycloakConnectionConfig) {
        logger.severe("Attempting to log into Keycloak at " + keycloakConnectionConfig.determineBaseUrl());
        keycloakClient.login(keycloakConnectionConfig.determineBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        keycloakClient.ensureRealm(KubeUtils.ENTANDO_KEYCLOAK_REALM);
    }

}
