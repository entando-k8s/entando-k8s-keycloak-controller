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
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import org.entando.kubernetes.controller.ExposedDeploymentResult;

public class KeycloakServiceDeploymentResult extends ExposedDeploymentResult<KeycloakServiceDeploymentResult> {

    private final Secret keycloakAdminSecret;

    public KeycloakServiceDeploymentResult(Pod pod, Service service,
            Ingress ingress, Secret keycloakAdminSecret) {
        super(pod, service, ingress);
        this.keycloakAdminSecret = keycloakAdminSecret;
    }

    public Secret getKeycloakAdminSecret() {
        return keycloakAdminSecret;
    }

}
