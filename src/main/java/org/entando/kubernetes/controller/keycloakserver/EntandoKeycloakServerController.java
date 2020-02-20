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
        DatabaseServiceResult databaseServiceResult = null;
        if (newEntandoKeycloakServer.getSpec().getDbms().orElse(DbmsVendor.NONE) != DbmsVendor.NONE) {
            databaseServiceResult = prepareDatabaseService(newEntandoKeycloakServer, newEntandoKeycloakServer.getSpec().getDbms().get(),
                    "db");
        }
        // Create the Keycloak service using the provided database
        KeycloakDeployable keycloakDeployable = new KeycloakDeployable(newEntandoKeycloakServer, databaseServiceResult);
        DeployCommand<ServiceDeploymentResult> keycloakCommand = new DeployCommand<>(keycloakDeployable);
        ServiceDeploymentResult serviceDeploymentResult = keycloakCommand.execute(k8sClient, Optional.of(keycloakClient));
        if (keycloakCommand.getPod() != null) {
            if (newEntandoKeycloakServer.getSpec().isDefault()) {
                copySecretToLocalNamespace(newEntandoKeycloakServer, serviceDeploymentResult);
            }
            ensureKeycloakRealm();
        }
        k8sClient.entandoResources().updateStatus(newEntandoKeycloakServer, keycloakCommand.getStatus());
    }

    private void ensureKeycloakRealm() {
        KeycloakConnectionConfig keycloakDeploymentResult = k8sClient.entandoResources().findKeycloak(Optional::empty);
        logger.severe("Attempting to log into Keycloak at " + keycloakDeploymentResult.getBaseUrl());
        keycloakClient.login(keycloakDeploymentResult.getBaseUrl(), keycloakDeploymentResult.getUsername(),
                keycloakDeploymentResult.getPassword());
        keycloakClient.ensureRealm(KubeUtils.ENTANDO_KEYCLOAK_REALM);
    }

    private void copySecretToLocalNamespace(EntandoKeycloakServer keycloakSever, ServiceDeploymentResult serviceDeploymentResult) {
        Secret kcSecret = k8sClient.secrets().loadSecret(keycloakSever, KeycloakDeployableContainer.secretName(keycloakSever));
        KeycloakConnectionConfig keycloakConnectionConfig = new KeycloakConnectionSecret(kcSecret);
        k8sClient.secrets().overwriteControllerSecret(new SecretBuilder()
                .withNewMetadata()
                .withName(EntandoOperatorConfig.getDefaultKeycloakSecretName())
                .endMetadata()
                .addToStringData(KubeUtils.USERNAME_KEY, keycloakConnectionConfig.getUsername())
                .addToStringData(KubeUtils.PASSSWORD_KEY, keycloakConnectionConfig.getPassword())
                .addToStringData(KubeUtils.URL_KEY, serviceDeploymentResult.getExternalBaseUrl())
                .build());
    }
}
