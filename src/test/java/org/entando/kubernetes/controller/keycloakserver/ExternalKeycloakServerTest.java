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
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedKeycloakCapability;
import org.entando.kubernetes.model.capability.CapabilityProvisioningStrategy;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.entando.kubernetes.test.common.SourceLink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure")})
@Feature(
        "As a controller developer, I would like deploy an EntandoKeycloakServer that merely references an externally provisioned SSO "
                + "service so that I "
                + "can leverage an existing user database")
@SourceLink("ExternalKeycloakServerTest.java")
class ExternalKeycloakServerTest extends KeycloakTestBase {

    public static final String MY_EXTERNAL_KC = "my-external-kc";

    @Test
    @Description("Should link to external service when all required fields are provided")
    void shouldLinkToExternalService() {
        step("Given I have configured a secret with admin credentials to a remote Keycloak server", () -> {
            final Secret adminSecret = new SecretBuilder()
                    .withNewMetadata()
                    .withNamespace(MY_NAMESPACE)
                    .withName("my-existing-sso-admin-secret")
                    .endMetadata()
                    .addToData(SecretUtils.USERNAME_KEY, "someuser")
                    .addToData(SecretUtils.PASSSWORD_KEY, "somepassword")
                    .build();
            getClient().secrets().createSecretIfAbsent(newResourceRequiringCapability(), adminSecret);
            attacheKubernetesResource("Existing Admin Secret", adminSecret);
        });
        step("When I request an SSO Capability  with its name and namespace explicitly specified, provisioned externally",
                () -> runControllerAgainst(new EntandoKeycloakServerBuilder()
                        .withNewMetadata()
                        .withName(MY_EXTERNAL_KC)
                        .withNamespace(MY_NAMESPACE)
                        .endMetadata()
                        .editSpec()
                        .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                        .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                        .withFrontEndUrl("https://kc.apps.serv.run/auth")
                        .withAdminSecretName("my-existing-sso-admin-secret")
                        .endSpec()
                        .build()));
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        step("Then ProvidedCapability was made available:", () -> {
            attacheKubernetesResource("EntandoKeycloakServer", providedCapability);
            step("using the 'Use External' provisioningStrategy",
                    () -> assertThat(providedCapability.getSpec().getProvisioningStrategy()).contains(
                            CapabilityProvisioningStrategy.USE_EXTERNAL));
            step("and it is owned by the EntandoKeycloakServer to ensure only changes from the EntandoKeycloakServer will change the "
                            + "implementing Kubernetes resources",
                    () -> assertThat(ResourceUtils.customResourceOwns(entandoKeycloakServer, providedCapability)));
            step("and its the specified externally provisioned service object reflects the connection info provided in the "
                            + "EntandoKeycloakServer",
                    () -> {
                        assertThat(providedCapability.getSpec().getExternallyProvisionedService().get().getHost())
                                .isEqualTo("kc.apps.serv.run");
                        assertThat(providedCapability.getSpec().getExternallyProvisionedService().get().getAdminSecretName())
                                .isEqualTo("my-existing-sso-admin-secret");
                        assertThat(providedCapability.getSpec().getExternallyProvisionedService().get().getPort()).contains(443);
                        assertThat(providedCapability.getSpec().getExternallyProvisionedService().get().getPath()).contains("/auth");
                    });
        });
        final ProvidedKeycloakCapability providedKeycloak = new ProvidedKeycloakCapability(
                client.capabilities().buildCapabilityProvisioningResult(providedCapability));
        step("And the provided Keycloak connection info reflects the external service",
                () -> assertThat(providedKeycloak.determineBaseUrl()).isEqualTo("https://kc.apps.serv.run/auth"));
    }

    @Test
    @Description("Should fail when the admin secret specified is absent in the deployment namespace")
    void shouldFailWhenAdminSecretAbsent() {
        step("Given I have not configured a secret with admin credentials to a remote Keycloak server");
        step("When I request an SSO Capability that is externally provided to a non-existing admin secret");
        step("Then an IllegalState exception is thrown by the CapabilityProvider", () ->
                assertThrows(CommandLine.ExecutionException.class,
                        () -> runControllerAgainst(new EntandoKeycloakServerBuilder()
                                .withNewMetadata()
                                .withName(MY_EXTERNAL_KC)
                                .withNamespace(MY_NAMESPACE)
                                .endMetadata()
                                .editSpec()
                                .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                                .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                                .withFrontEndUrl("https://kc.apps.serv.run:8080/auth")
                                .withAdminSecretName("my-existing-sso-admin-secret")
                                .endSpec()
                                .build())));
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        step("And the resulting status objects of both the ProvidedCapability and EntandoKeycloakServer reflect the failure and the cause"
                        + " for the failure",
                () -> {
                    attacheKubernetesResource("EntandoKeycloakServer.status", entandoKeycloakServer.getStatus());
                    attacheKubernetesResource("ProvidedCapability.status", providedCapability.getStatus());
                    step("The phase of the statuses of both the ProvidedCapability and EntandoKeycloakServer is FAILED", () -> {
                        assertThat(entandoKeycloakServer.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
                        assertThat(providedCapability.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
                    });
                    step("And the statuses of  both the ProvidedCapability and EntandoKeycloakServer reflect the correct error message",
                            () -> {
                                assertThat(entandoKeycloakServer.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please ensure that a secret with the name 'my-existing-sso-admin-secret' exists in the requested"
                                                + " namespace");
                                assertThat(providedCapability.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please ensure that a secret with the name 'my-existing-sso-admin-secret' exists in the requested"
                                                + " namespace");
                            });
                });
    }

    @Test
    @Description("Should fail when no frontEndUrl is specified")
    void shouldFailWhenNoFrontEndUrlSpecified() {
        step("Given I have configured a secret with admin credentials to a remote Keycloak server", () -> {
            final Secret adminSecret = new SecretBuilder()
                    .withNewMetadata()
                    .withNamespace(MY_NAMESPACE)
                    .withName("my-existing-sso-admin-secret")
                    .endMetadata()
                    .addToData(SecretUtils.USERNAME_KEY, "someuser")
                    .addToData(SecretUtils.PASSSWORD_KEY, "somepassword")
                    .build();
            getClient().secrets().createSecretIfAbsent(newResourceRequiringCapability(), adminSecret);
            attacheKubernetesResource("Existing Admin Secret", adminSecret);
        });
        step("When I request an SSO Capability that is externally provided to a non-existing admin secret");
        step("Then an IllegalState exception is thrown by the CapabilityProvider", () ->
                assertThrows(CommandLine.ExecutionException.class,
                        () -> runControllerAgainst(new EntandoKeycloakServerBuilder()
                                .withNewMetadata()
                                .withName(MY_EXTERNAL_KC)
                                .withNamespace(MY_NAMESPACE)
                                .endMetadata()
                                .editSpec()
                                .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                                .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                                .withFrontEndUrl(null)//NO FRONTENDURL
                                .withAdminSecretName("my-existing-sso-admin-secret")
                                .endSpec()
                                .build())));
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        step("And the resulting status objects of both the ProvidedCapability and EntandoKeycloakServer reflect the failure and the cause"
                        + " for the failure",
                () -> {
                    attacheKubernetesResource("EntandoKeycloakServer.status", entandoKeycloakServer.getStatus());
                    attacheKubernetesResource("ProvidedCapability.status", providedCapability.getStatus());
                    step("The phase of the statuses of both the ProvidedCapability and EntandoKeycloakServer is FAILED", () -> {
                        assertThat(entandoKeycloakServer.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
                        assertThat(providedCapability.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
                    });
                    step("And the statuses of  both the ProvidedCapability and EntandoKeycloakServer reflect the correct error message",
                            () -> {
                                assertThat(entandoKeycloakServer.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please provide the base URL of the SSO service you intend to connect to");
                                assertThat(providedCapability.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please provide the base URL of the SSO service you intend to connect to");
                            });
                });
    }

    @Test
    @Description("Should fail when no admin secret name is specified")
    void shouldFailWhenNoAdminSecretName() {
        step("Given I have configured a secret with admin credentials to a remote Keycloak server", () -> {
            final Secret adminSecret = new SecretBuilder()
                    .withNewMetadata()
                    .withNamespace(MY_NAMESPACE)
                    .withName("my-existing-sso-admin-secret")
                    .endMetadata()
                    .addToData(SecretUtils.USERNAME_KEY, "someuser")
                    .addToData(SecretUtils.PASSSWORD_KEY, "somepassword")
                    .build();
            getClient().secrets().createSecretIfAbsent(newResourceRequiringCapability(), adminSecret);
            attacheKubernetesResource("Existing Admin Secret", adminSecret);
        });
        step("When I request an SSO Capability that is externally provided to a non-existing admin secret");
        step("Then an IllegalState exception is thrown by the CapabilityProvider", () ->
                assertThrows(CommandLine.ExecutionException.class,
                        () -> runControllerAgainst(new EntandoKeycloakServerBuilder()
                                .withNewMetadata()
                                .withName(MY_EXTERNAL_KC)
                                .withNamespace(MY_NAMESPACE)
                                .endMetadata()
                                .editSpec()
                                .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                                .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                                .withFrontEndUrl("https://kc.apps.serv.run:8080/auth")
                                .withAdminSecretName(null)//NO ADMINSECRET
                                .endSpec()
                                .build())));
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, MY_EXTERNAL_KC);
        step("And the resulting status objects of both the ProvidedCapability and EntandoKeycloakServer reflect the failure and the cause"
                        + " for the failure",
                () -> {
                    attacheKubernetesResource("EntandoKeycloakServer.status", entandoKeycloakServer.getStatus());
                    attacheKubernetesResource("ProvidedCapability.status", providedCapability.getStatus());
                    step("The phase of the statuses of both the ProvidedCapability and EntandoKeycloakServer is FAILED", () -> {
                        assertThat(entandoKeycloakServer.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
                        assertThat(providedCapability.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
                    });
                    step("And the statuses of  both the ProvidedCapability and EntandoKeycloakServer reflect the correct error message",
                            () -> {
                                assertThat(entandoKeycloakServer.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please provide the name of the secret containing the admin credentials for the SSO service you "
                                                + "intend to "
                                                + "connect to");
                                assertThat(providedCapability.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please provide the name of the secret containing the admin credentials for the SSO service you "
                                                + "intend to "
                                                + "connect to");
                            });
                });
    }
}
