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

import static java.util.Optional.ofNullable;
import static org.entando.kubernetes.controller.spi.common.SecretUtils.generateSecret;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.deployable.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.deployable.ExternalService;
import org.entando.kubernetes.controller.spi.deployable.Secretive;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.controller.support.spibase.IngressingDeployableBase;
import org.entando.kubernetes.model.capability.CapabilityProvisioningStrategy;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;

public class KeycloakDeployable
        implements IngressingDeployableBase<KeycloakDeploymentResult>, DbAwareDeployable<KeycloakDeploymentResult>,
        Secretive {

    public static final long KEYCLOAK_IMAGE_DEFAULT_USERID = 1000L;
    public static final long REDHAT_SSO_IMAGE_DEFAULT_USERID = 185L;
    private final EntandoKeycloakServer keycloakServer;
    private final List<DeployableContainer> containers;
    private Secret keycloakAdminSecret;

    public KeycloakDeployable(EntandoKeycloakServer keycloakServer, DatabaseConnectionInfo databaseServiceResult, Secret caCertSecret) {
        this.keycloakServer = keycloakServer;
        this.containers = Collections.singletonList(new KeycloakDeployableContainer(keycloakServer, databaseServiceResult, caCertSecret));
        if (keycloakServer.getSpec().getAdminSecretName().isEmpty()) {
            this.keycloakAdminSecret = generateSecret(
                    this.keycloakServer,
                    KeycloakDeployableContainer.secretName(this.keycloakServer),
                    "entando_keycloak_admin");
        }
    }

    @Override
    public boolean isIngressRequired() {
        return keycloakServer.getSpec().getProvisioningStrategy().map(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY::equals).orElse(true);
    }

    @Override
    public Optional<ExternalService> getExternalService() {
        if (keycloakServer.getSpec().getProvisioningStrategy().orElse(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY)
                == CapabilityProvisioningStrategy.USE_EXTERNAL) {
            final String frontEndUrl = keycloakServer.getSpec().getFrontEndUrl().orElseThrow(IllegalStateException::new);
            return Optional.of(new ExternalKeycloakService(frontEndUrl));
        }
        return IngressingDeployableBase.super.getExternalService();
    }

    @Override
    public Optional<Long> getFileSystemUserAndGroupId() {
        if (EntandoKeycloakHelper.determineStandardImage(keycloakServer) == StandardKeycloakImage.KEYCLOAK) {
            return Optional.of(KEYCLOAK_IMAGE_DEFAULT_USERID);
        } else {
            return Optional.of(REDHAT_SSO_IMAGE_DEFAULT_USERID);
        }
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public Optional<String> getQualifier() {
        return Optional.empty();
    }

    @Override
    public EntandoKeycloakServer getCustomResource() {
        return keycloakServer;
    }

    @Override
    public KeycloakDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new KeycloakDeploymentResult(pod, service, ingress, KeycloakDeployableContainer.secretName(keycloakServer), keycloakServer);
    }

    @Override
    public String getServiceAccountToUse() {
        return keycloakServer.getSpec().getServiceAccountToUse().orElse(getDefaultServiceAccountName());
    }

    @Override
    public int getReplicas() {
        return keycloakServer.getSpec().getReplicas().orElse(1);
    }

    @Override
    public String getIngressName() {
        return NameUtils.standardIngressName(keycloakServer);
    }

    @Override
    public String getIngressNamespace() {
        return keycloakServer.getMetadata().getNamespace();
    }

    @Override
    public List<Secret> getSecrets() {
        return ofNullable(keycloakAdminSecret).stream().collect(Collectors.toList());
    }

}
