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

import static io.qameta.allure.Allure.step;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.extensions.IngressBuilder;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Allure;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.capability.CapabilityRequirementWatcher;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.command.CommandStream;
import org.entando.kubernetes.controller.spi.common.ConfigProperty;
import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedDatabaseCapability;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.client.doubles.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.command.InProcessCommandStream;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.model.capability.CapabilityRequirement;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.AbstractServerStatus;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.common.ExposedServerStatus;
import org.entando.kubernetes.model.common.InternalServerStatus;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import picocli.CommandLine;

public interface ControllerTestHelper {

    String DEFAULT_TLS_SECRET = "default-tls-secret";
    String MY_APP = "my-app";
    String MY_NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("my-namespace");

    SimpleK8SClient<EntandoResourceClientDouble> getClient();

    ScheduledExecutorService getScheduler();

    default Optional<SimpleKeycloakClient> getKeycloakClient() {
        return java.util.Optional.empty();
    }

    default void runControllerAgainst(HasMetadata forResource, CapabilityRequirement capabilityRequirement) {
        attacheKubernetesResource("Resource Requesting Capability", forResource);
        attacheKubernetesResource("Capability Requirement", capabilityRequirement);
        final StandardCapability capability = capabilityRequirement.getCapability();
        Mockito.when(getClient().capabilities().createAndWatchResource(argThat(matchesCapability(capability)), any()))
                .thenAnswer(invocationOnMock -> {
                    try {
                        final Object result = invocationOnMock.callRealMethod();
                        getScheduler().schedule(() -> runControllerAndUpdateCapabilityStatus(invocationOnMock.getArgument(0),
                                invocationOnMock.getArgument(1)), 100, TimeUnit.MILLISECONDS);
                        return result;
                    } catch (Throwable throwable) {
                        throw new IllegalStateException(throwable);
                    }
                });
        new CapabilityProvider(getClient().capabilities()).provideCapability(forResource, capabilityRequirement);
    }

    default void runControllerAgainst(EntandoCustomResource entandoCustomResource) {
        attacheKubernetesResource("Resource Providing Capability", entandoCustomResource);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME.getJvmSystemProperty(),
                entandoCustomResource.getMetadata().getName());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE.getJvmSystemProperty(),
                entandoCustomResource.getMetadata().getNamespace());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_KIND.getJvmSystemProperty(), entandoCustomResource.getKind());
        final SimpleK8SClient<EntandoResourceClientDouble> client = this.getClient();
        client.entandoResources().createOrPatchEntandoResource(entandoCustomResource);
        final SimpleKeycloakClient keycloakClient = this.getKeycloakClient().orElse(null);
        final AllureAttachingCommandStream commandStream = new AllureAttachingCommandStream(client, keycloakClient);
        Runnable controller = createController(client, keycloakClient, commandStream);
        controller.run();
    }

    default ArgumentMatcher<ProvidedCapability> matchesCapability(StandardCapability capability) {
        return t -> t != null && t.getSpec().getCapability() == capability;
    }

    private void runControllerAndUpdateCapabilityStatus(ProvidedCapability providedCapability, CapabilityRequirementWatcher watcher) {
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME.getJvmSystemProperty(),
                providedCapability.getMetadata().getName());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE.getJvmSystemProperty(),
                providedCapability.getMetadata().getNamespace());
        System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_KIND.getJvmSystemProperty(), providedCapability.getKind());
        final SimpleK8SClient<EntandoResourceClientDouble> client = this.getClient();
        final SimpleKeycloakClient keycloakClient = this.getKeycloakClient().orElse(null);
        final AllureAttachingCommandStream commandStream = new AllureAttachingCommandStream(client, keycloakClient);
        Runnable controller = createController(client, keycloakClient, commandStream);
        try {
            controller.run();
            watcher.eventReceived(Action.ADDED, client.entandoResources()
                    .load(ProvidedCapability.class, providedCapability.getMetadata().getNamespace(),
                            providedCapability.getMetadata().getName()));
        } catch (CommandLine.ExecutionException e) {
            watcher.eventReceived(Action.ERROR, client.entandoResources()
                    .load(ProvidedCapability.class, providedCapability.getMetadata().getNamespace(),
                            providedCapability.getMetadata().getName()));

        }
    }

    Runnable createController(SimpleK8SClient<EntandoResourceClientDouble> client, SimpleKeycloakClient keycloakClient,
            CommandStream commandStream);

    default void attacheKubernetesResource(String name, Object resource) {
        try {
            Allure.attachment(name, new ObjectMapper(new YAMLFactory()).writeValueAsString(resource));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    default void attachEnvironmentVariable(ConfigProperty prop, String value) {
        System.setProperty(prop.getJvmSystemProperty(), value);
        Allure.attachment("Environment Variable", prop.name() + "=" + value);
    }

    default ProvidedCapability putInternalServerStatus(SimpleK8SClient<?> client, ProvidedCapability providedCapability, int port,
            Map<String, String> derivedDeploymentParameters) {
        return putStatus(client, providedCapability, port, derivedDeploymentParameters, new InternalServerStatus("main"));
    }

    default Answer<Object> withADatabaseCapabiltyStatus(DbmsVendor vendor, String databaseNAme) {
        return invocationOnMock -> {
            getScheduler().schedule(() -> {
                CapabilityRequirementWatcher watcher = invocationOnMock.getArgument(1);

                Map<String, String> derivedParameters = new HashMap<>();
                derivedParameters.put(ProvidedDatabaseCapability.DATABASE_NAME_PARAMETER, databaseNAme);
                derivedParameters.put(ProvidedDatabaseCapability.DBMS_VENDOR_PARAMETER, vendor.name().toLowerCase(Locale.ROOT));
                final ProvidedCapability resource = putInternalServerStatus(getClient(), invocationOnMock.getArgument(0),
                        DbmsVendorConfig.valueOf(vendor.name()).getDefaultPort(),
                        derivedParameters);
                watcher.eventReceived(Action.MODIFIED, resource);
            }, 200L, TimeUnit.MILLISECONDS);
            return invocationOnMock.callRealMethod();
        };
    }

    default ProvidedCapability putExternalServerStatus(SimpleK8SClient<EntandoResourceClientDouble> client,
            ProvidedCapability providedCapability,
            int port,
            Map<String, String> derivedParameters) {
        final ExposedServerStatus status = new ExposedServerStatus(NameUtils.MAIN_QUALIFIER);
        status.setIngressName(client.ingresses().createIngress(providedCapability, new IngressBuilder()
                .withNewMetadata()
                .withNamespace(providedCapability.getMetadata().getNamespace())
                .withName(providedCapability.getMetadata().getName() + "-" + NameUtils.DEFAULT_INGRESS_SUFFIX)
                .endMetadata()
                .withNewSpec()
                .addNewRule()
                .withNewHttp()
                .addNewPath()
                .withNewBackend()
                .withServiceName(providedCapability.getMetadata().getName() + "-" + NameUtils.DEFAULT_SERVICE_SUFFIX)
                .withServicePort(new IntOrString(port))
                .endBackend()
                .withPath("/non-existing")
                .endPath()
                .endHttp()
                .endRule()
                .endSpec()
                .build()).getMetadata().getName());
        return putStatus(client, providedCapability, port, derivedParameters, status);
    }

    private ProvidedCapability putStatus(SimpleK8SClient<?> client, ProvidedCapability providedCapability, int port,
            Map<String, String> derivedDeploymentParameters, AbstractServerStatus status) {
        providedCapability.getStatus().putServerStatus(status);
        status.setServiceName(client.services().createOrReplaceService(providedCapability, new ServiceBuilder()
                .withNewMetadata()
                .withNamespace(providedCapability.getMetadata().getNamespace())
                .withName(providedCapability.getMetadata().getName() + "-" + NameUtils.DEFAULT_SERVICE_SUFFIX)
                .endMetadata()
                .withNewSpec()
                .addNewPort()
                .withPort(port)
                .endPort()
                .endSpec()
                .build()).getMetadata().getName());
        final Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(providedCapability.getMetadata().getNamespace())
                .withName(providedCapability.getMetadata().getName() + "-admin-secret")
                .endMetadata()
                .addToStringData("username", "jon")
                .addToStringData("password", "password123")
                .build();
        client.secrets().createSecretIfAbsent(providedCapability, secret);
        status.setAdminSecretName(secret.getMetadata().getName());
        derivedDeploymentParameters.forEach(status::putDerivedDeploymentParameter);
        client.entandoResources().updateStatus(providedCapability, status);
        client.entandoResources().updatePhase(providedCapability, EntandoDeploymentPhase.SUCCESSFUL);
        return client.entandoResources().reload(providedCapability);
    }

    class AllureAttachingCommandStream extends InProcessCommandStream {

        public AllureAttachingCommandStream(SimpleK8SClient<EntandoResourceClientDouble> client, SimpleKeycloakClient keycloakClient) {
            super(client, keycloakClient);
        }

        @Override
        public String process(String deployable) {
            Allure.attachment("Input Deployable", deployable);
            final String result = super.process(deployable);
            Allure.attachment("Output Result", result);
            return result;
        }
    }

    default SerializedEntandoResource newResourceRequiringCapability() {
        final SerializedEntandoResource entandoResource = new SerializedEntandoResource();
        entandoResource.setMetadata(new ObjectMetaBuilder()
                .withName(MY_APP)
                .withNamespace(MY_NAMESPACE)
                .build());
        final CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                .withGroup("entando.org")
                .withKind("EntandoApp")
                .withVersion("v1")
                .build();
        entandoResource.setDefinition(ctx);
        return entandoResource;
    }

    default void aTlsSecretWasCreatedAndConfiguredAsDefault() {
        step("And a TLS Secret was created and configured as default", () -> {
            attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_TLS_SECRET_NAME, DEFAULT_TLS_SECRET);
            getClient().secrets().overwriteControllerSecret(new SecretBuilder()
                    .withNewMetadata()
                    .withName(DEFAULT_TLS_SECRET)
                    .withNamespace(MY_NAMESPACE)
                    .endMetadata()
                    .withType("kubernetes.io/tls")
                    .addToData("tls.crt", "")
                    .addToData("tls.key", "")
                    .build());
        });
    }
}
