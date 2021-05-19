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
import io.qameta.allure.Story;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedKeycloakCapability;
import org.entando.kubernetes.model.capability.CapabilityProvisioningStrategy;
import org.entando.kubernetes.model.capability.CapabilityRequirement;
import org.entando.kubernetes.model.capability.CapabilityRequirementBuilder;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.common.ExposedServerStatus;
import org.entando.kubernetes.model.common.ResourceReference;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.test.common.SourceLink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure")})
@Feature("As a controller developer, I would like request a capability that will allow me to use an external Keycloak service so that I "
        + "can leverage an existing user database")
@SourceLink("ExternalKeycloakCapabilityTest.java")
class ExternalKeycloakCapabilityTest extends KeycloakTestBase {

    public static final String SPECIFIED_SSO = "specified-sso";

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
                () -> runControllerAgainst(newResourceRequiringCapability(), new CapabilityRequirementBuilder()
                        .withCapability(StandardCapability.SSO)
                        .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                        .withCapabilityRequirementScope(CapabilityScope.SPECIFIED)
                        .withNewExternallyProvidedService()
                        .withPath("/auth")
                        .withHost("kc.apps.serv.run")
                        .withPort(8080)
                        .withAdminSecretName("my-existing-sso-admin-secret")
                        .endExternallyProvidedService()
                        .withSpecifiedCapability(new ResourceReference(MY_NAMESPACE, SPECIFIED_SSO))
                        .build()));
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, SPECIFIED_SSO);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, SPECIFIED_SSO);
        step("Then an EntandoKeycloakServer was provisioned:", () -> {
            step("with the name explicitly specified", () -> {
                assertThat(entandoKeycloakServer.getMetadata().getName()).isEqualTo(SPECIFIED_SSO);
                assertThat(providedCapability.getMetadata().getName()).isEqualTo(SPECIFIED_SSO);
                assertThat(providedCapability.getSpec().getSpecifiedCapability().get().getName()).isEqualTo(SPECIFIED_SSO);
            });

            step("using the 'Use External' provisioningStrategy",
                    () -> assertThat(entandoKeycloakServer.getSpec().getProvisioningStrategy()).contains(
                            CapabilityProvisioningStrategy.USE_EXTERNAL));
            step("and it is owned by the ProvidedCapability to ensure only changes from the ProvidedCapability will change the "
                            + "implementing Kubernetes resources",
                    () -> assertThat(ResourceUtils.customResourceOwns(providedCapability, entandoKeycloakServer)));
            step("and its frontEndUrl property reflects the connection info provided in the CapabilityRequirement",
                    () -> assertThat(entandoKeycloakServer.getSpec().getFrontEndUrl()).contains("https://kc.apps.serv.run:8080/auth"));
            step("and the ProvidedCapability's status carries the name of the correct admin secret to use",
                    () -> assertThat(providedCapability.getStatus().findCurrentServerStatus().get().getAdminSecretName())
                            .contains("my-existing-sso-admin-secret"));
            step("and the ProvidedCapability's status carries the base url where the SSO service can be accessed",
                    () -> assertThat(
                            ((ExposedServerStatus) providedCapability.getStatus().findCurrentServerStatus().get()).getExternalBaseUrl())
                            .isEqualTo("https://kc.apps.serv.run:8080/auth"));
            attacheKubernetesResource("EntandoKeycloakServer", entandoKeycloakServer);
        });
        final ProvidedKeycloakCapability providedKeycloak = new ProvidedKeycloakCapability(
                client.capabilities().buildCapabilityProvisioningResult(providedCapability));
        step("And the provided Keycloak connection info reflects the external service", () -> {

            step("using the 'Use External' provisioningStrategy",
                    () -> assertThat(providedKeycloak.determineBaseUrl()).isEqualTo("https://kc.apps.serv.run:8080/auth"));
        });
    }

    @Test
    @Description("Should fail when the admin secret specified is absent in the deployment namespace")
    void shouldFailWhenAdminSecretAbsent() {
        step("Given I have configured not configured a secret with admin credentials to a remote Keycloak server");
        step("When I request an SSO Capability that is externally provided to a non-existing admin secret");
        step("Then an IllegalState exception is thrown by the CapabilityProvider", () -> {
            final SerializedEntandoResource forResource = newResourceRequiringCapability();
            final CapabilityRequirement build = new CapabilityRequirementBuilder()
                    .withCapability(StandardCapability.SSO)
                    .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                    .withCapabilityRequirementScope(CapabilityScope.NAMESPACE)
                    .withNewExternallyProvidedService()
                    .withPath("/auth")
                    .withHost("kc.apps.serv.run")
                    .withPort(8080)
                    .withAdminSecretName("my-existing-sso-admin-secret")
                    .endExternallyProvidedService()
                    .build();
            assertThrows(IllegalStateException.class,
                    () -> runControllerAgainst(forResource, build));
        });
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, DEFAULT_SSO_IN_NAMESPACE);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, DEFAULT_SSO_IN_NAMESPACE);
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
    @Description("Should fail when no host name is specified")
    void shouldFailWhenNoHostNameSpecified() {
        final SerializedEntandoResource forResource = newResourceRequiringCapability();
        step("Given I have configured a secret with admin credentials to a remote Keycloak server", () -> {
            final Secret adminSecret = new SecretBuilder()
                    .withNewMetadata()
                    .withNamespace(MY_NAMESPACE)
                    .withName("my-existing-sso-admin-secret")
                    .endMetadata()
                    .addToData(SecretUtils.USERNAME_KEY, "someuser")
                    .addToData(SecretUtils.PASSSWORD_KEY, "somepassword")
                    .build();
            getClient().secrets().createSecretIfAbsent(forResource, adminSecret);
            attacheKubernetesResource("Existing Admin Secret", adminSecret);
        });
        step("When I request an SSO Capability that is externally provided to a non-existing admin secret");
        step("Then an IllegalState exception is thrown by the CapabilityProvider", () -> {
            final CapabilityRequirement capabilityRequirement = new CapabilityRequirementBuilder()
                    .withCapability(StandardCapability.SSO)
                    .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                    .withCapabilityRequirementScope(CapabilityScope.NAMESPACE)
                    .withNewExternallyProvidedService()
                    .withPath("/auth")
                    .withHost(null)//NO HOST!!!
                    .withPort(8080)
                    .withAdminSecretName("my-existing-sso-admin-secret")
                    .endExternallyProvidedService()
                    .build();
            assertThrows(IllegalStateException.class,
                    () -> runControllerAgainst(forResource, capabilityRequirement));
        });
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, DEFAULT_SSO_IN_NAMESPACE);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, DEFAULT_SSO_IN_NAMESPACE);
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
                                        "Please provide the hostname of the SSO service you intend to connect to");
                                assertThat(providedCapability.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please provide the hostname of the SSO service you intend to connect to");
                            });
                });
    }

    @Test
    @Description("Should fail when no admin secret name is specified")
    void shouldFailWhenNoAdminSecretName() {
        final SerializedEntandoResource forResource = newResourceRequiringCapability();
        step("Given I have configured a secret with admin credentials to a remote Keycloak server", () -> {
            final Secret adminSecret = new SecretBuilder()
                    .withNewMetadata()
                    .withNamespace(MY_NAMESPACE)
                    .withName("my-existing-sso-admin-secret")
                    .endMetadata()
                    .addToData(SecretUtils.USERNAME_KEY, "someuser")
                    .addToData(SecretUtils.PASSSWORD_KEY, "somepassword")
                    .build();
            getClient().secrets().createSecretIfAbsent(forResource, adminSecret);
            attacheKubernetesResource("Existing Admin Secret", adminSecret);
        });
        step("When I request an SSO Capability that is externally provided to a non-existing admin secret");
        step("Then an IllegalState exception is thrown by the CapabilityProvider", () -> {
            final CapabilityRequirement capabilityRequirement = new CapabilityRequirementBuilder()
                    .withCapability(StandardCapability.SSO)
                    .withProvisioningStrategy(CapabilityProvisioningStrategy.USE_EXTERNAL)
                    .withCapabilityRequirementScope(CapabilityScope.NAMESPACE)
                    .withNewExternallyProvidedService()
                    .withPath("/auth")
                    .withHost("myhost.com")
                    .withPort(8080)
                    .withAdminSecretName(null)//NO ADMIN SECRET!!
                    .endExternallyProvidedService()
                    .build();
            assertThrows(IllegalStateException.class, () -> runControllerAgainst(forResource, capabilityRequirement));
        });
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, DEFAULT_SSO_IN_NAMESPACE);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, DEFAULT_SSO_IN_NAMESPACE);
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
                                        "Please provide the name of the secret containing the admin credentials server you intend to "
                                                + "connect to");
                                assertThat(providedCapability.getStatus().findCurrentServerStatus().get().getEntandoControllerFailure()
                                        .getDetailMessage()).contains(
                                        "Please provide the name of the secret containing the admin credentials server you intend to "
                                                + "connect to");
                            });
                });
    }
}
