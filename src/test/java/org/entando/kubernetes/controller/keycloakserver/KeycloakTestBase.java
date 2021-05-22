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

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.capability.SerializingCapabilityProvider;
import org.entando.kubernetes.controller.spi.command.DeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.client.doubles.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.test.common.ControllerTestHelper;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.junit.jupiter.api.AfterEach;
import org.mockito.Mock;

abstract class KeycloakTestBase implements FluentTraversals, ControllerTestHelper {

    public static final String DEFAULT_SSO_IN_NAMESPACE = "default-sso-in-namespace";

    protected final SimpleK8SClientDouble client = new SimpleK8SClientDouble();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5);
    private CapabilityProvider capabilityProvider;
    @Mock
    private SimpleKeycloakClient keycloakClient;

    public CapabilityProvider getCapabilityProvider() {
        return new SerializingCapabilityProvider(getClient().entandoResources(), new AllureAttachingCommandStream(client,
                getKeycloakClient().orElseThrow(IllegalStateException::new)));
    }

    @Override
    public Optional<SimpleKeycloakClient> getKeycloakClient() {
        return Optional.of(keycloakClient);
    }

    @Override
    public SimpleK8SClientDouble getClient() {
        return client;
    }

    @Override
    public ScheduledExecutorService getScheduler() {
        return scheduledExecutorService;
    }

    @AfterEach
    void resetSystemProps() {
        System.getProperties().remove(EntandoOperatorSpiConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty());
        System.getProperties().remove(EntandoOperatorSpiConfigProperty.ENTANDO_CA_SECRET_NAME.getJvmSystemProperty());
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_TLS_SECRET_NAME.getJvmSystemProperty());
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_DOCKER_IMAGE_VERSION_FALLBACK.getJvmSystemProperty());
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE.getJvmSystemProperty());
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_INGRESS_CLASS.getJvmSystemProperty());
        System.getProperties()
                .remove(EntandoOperatorSpiConfigProperty.ENTANDO_K8S_OPERATOR_DEFAULT_CLUSTERED_STORAGE_CLASS.getJvmSystemProperty());
        System.getProperties()
                .remove(EntandoOperatorSpiConfigProperty.ENTANDO_K8S_OPERATOR_DEFAULT_NON_CLUSTERED_STORAGE_CLASS.getJvmSystemProperty());
    }

    @Override
    public Runnable createController(DeploymentProcessor deploymentProcessor) {
        return new EntandoKeycloakServerController(getClient().entandoResources(), deploymentProcessor, getCapabilityProvider(),
                getKeycloakClient().orElseThrow(IllegalStateException::new));
    }
}
