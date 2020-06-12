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

import static org.entando.kubernetes.controller.KubeUtils.generateSecret;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.commons.io.IOUtils;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.spi.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.IngressingDeployable;
import org.entando.kubernetes.controller.spi.Secretive;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;

public class KeycloakDeployable implements IngressingDeployable<ServiceDeploymentResult>, DbAwareDeployable, Secretive {

    private final EntandoKeycloakServer keycloakServer;
    private final List<DeployableContainer> containers;
    private final DatabaseServiceResult databaseServiceResult;
    private final Secret keycloakAdminSecret;
    private final Secret realmBase;

    public KeycloakDeployable(EntandoKeycloakServer keycloakServer, DatabaseServiceResult databaseServiceResult) {
        this.keycloakServer = keycloakServer;
        this.databaseServiceResult = databaseServiceResult;
        containers = Arrays.asList(new KeycloakDeployableContainer(keycloakServer, databaseServiceResult));
        keycloakAdminSecret = generateSecret(this.keycloakServer, KeycloakDeployableContainer.secretName(this.keycloakServer),
                "entando_keycloak_admin");
        realmBase = buildRealmJson();
    }

    private Secret buildRealmJson() {
        try {
            return new SecretBuilder().withNewMetadata().withName(this.keycloakServer.getMetadata().getName() + "-realm").endMetadata()
                    .addToStringData("realm.json", IOUtils.resourceToString("/realm-base.json", StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean hasContainersExpectingSchemas() {
        return keycloakServer.getSpec().getDbms().map(v -> v != DbmsVendor.NONE).orElse(false);
    }

    @Override
    public Optional<Long> getFileSystemUserAndGroupId() {
        return Optional.of(1000L);
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public DatabaseServiceResult getDatabaseServiceResult() {
        return databaseServiceResult;
    }

    @Override
    public String getNameQualifier() {
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public EntandoCustomResource getCustomResource() {
        return keycloakServer;
    }

    @Override
    public ServiceDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new ServiceDeploymentResult(service, ingress);
    }

    @Override
    public int getReplicas() {
        return keycloakServer.getSpec().getReplicas().orElse(1);
    }

    @Override
    public String getIngressName() {
        return KubeUtils.standardIngressName(keycloakServer);
    }

    @Override
    public String getIngressNamespace() {
        return keycloakServer.getMetadata().getNamespace();
    }

    @Override
    public List<Secret> buildSecrets() {
        return Arrays.asList(keycloakAdminSecret, realmBase);
    }
}
