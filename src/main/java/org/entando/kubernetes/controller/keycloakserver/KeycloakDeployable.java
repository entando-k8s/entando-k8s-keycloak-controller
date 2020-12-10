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
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.spi.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.IngressingDeployable;
import org.entando.kubernetes.controller.spi.Secretive;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerSpec;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;

public class KeycloakDeployable implements IngressingDeployable<KeycloakServiceDeploymentResult, EntandoKeycloakServerSpec>,
        DbAwareDeployable,
        Secretive {

    public static final long KEYCLOAK_IMAGE_DEFAULT_USERID = 1000L;
    public static final long REDHAT_SSO_IMAGE_DEFAULT_USERID = 185L;
    private final EntandoKeycloakServer keycloakServer;
    private final List<DeployableContainer> containers;
    private final DatabaseServiceResult databaseServiceResult;
    private final Secret keycloakAdminSecret;

    public KeycloakDeployable(EntandoKeycloakServer keycloakServer,
            DatabaseServiceResult databaseServiceResult,
            Secret existingKeycloakAdminSecret) {
        this.keycloakServer = keycloakServer;
        this.databaseServiceResult = databaseServiceResult;
        this.containers = Arrays.asList(new KeycloakDeployableContainer(keycloakServer, databaseServiceResult));
        this.keycloakAdminSecret = generateSecret(
                this.keycloakServer,
                KeycloakDeployableContainer.secretName(this.keycloakServer),
                "entando_keycloak_admin");
        this.keycloakAdminSecret.setStringData(existingKeycloakAdminSecret.getStringData());
        this.keycloakAdminSecret.setData(existingKeycloakAdminSecret.getData());
    }

    @Override
    public boolean hasContainersExpectingSchemas() {
        return keycloakServer.getSpec().getDbms().map(v -> v != DbmsVendor.NONE && v != DbmsVendor.EMBEDDED).orElse(false);
    }

    @Override
    public Optional<Long> getFileSystemUserAndGroupId() {
        if(keycloakServer.getSpec().getStandardImage().orElse(StandardKeycloakImage.KEYCLOAK)==StandardKeycloakImage.KEYCLOAK){
            return Optional.of(KEYCLOAK_IMAGE_DEFAULT_USERID);
        }else{
            return Optional.of(REDHAT_SSO_IMAGE_DEFAULT_USERID);
        }
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
    public EntandoKeycloakServer getCustomResource() {
        return keycloakServer;
    }

    @Override
    public KeycloakServiceDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new KeycloakServiceDeploymentResult(pod, service, ingress);
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
        return Arrays.asList(keycloakAdminSecret);
    }
}
