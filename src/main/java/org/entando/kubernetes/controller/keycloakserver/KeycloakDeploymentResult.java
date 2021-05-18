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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import org.entando.kubernetes.controller.spi.result.ExposedDeploymentResult;
import org.entando.kubernetes.model.common.AbstractServerStatus;
import org.entando.kubernetes.model.common.ExposedServerStatus;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;

public class KeycloakDeploymentResult extends ExposedDeploymentResult<KeycloakDeploymentResult> {

    private final EntandoKeycloakServer entandoKeycloakServer;

    public KeycloakDeploymentResult(Pod pod, Service service, Ingress ingress, String adminSecretName,
            EntandoKeycloakServer entandoKeycloakServer) {
        super(pod, service, ingress);
        super.adminSecretName = adminSecretName;
        this.entandoKeycloakServer = entandoKeycloakServer;
    }

    @Override
    public KeycloakDeploymentResult withStatus(AbstractServerStatus status) {
        final ExposedServerStatus exposedServerStatus = (ExposedServerStatus) status;
        exposedServerStatus.setAdminSecretName(adminSecretName);
        //orElseGet avoids a NPE
        exposedServerStatus.setExternalBaseUrl(entandoKeycloakServer.getSpec().getFrontEndUrl().orElseGet(this::getExternalBaseUrl));
        return super.withStatus(status);
    }
}
