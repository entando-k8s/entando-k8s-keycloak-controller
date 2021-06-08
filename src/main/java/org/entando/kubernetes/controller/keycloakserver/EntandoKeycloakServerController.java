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

import static java.lang.String.format;
import static java.util.Optional.ofNullable;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvisioningResult;
import org.entando.kubernetes.controller.spi.client.ExecutionResult;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.DeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.EntandoControllerException;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.spi.container.ProvidedDatabaseCapability;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.model.capability.CapabilityProvisioningStrategy;
import org.entando.kubernetes.model.capability.CapabilityRequirement;
import org.entando.kubernetes.model.capability.CapabilityRequirementBuilder;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.ExternallyProvidedService;
import org.entando.kubernetes.model.capability.ExternallyProvidedServiceFluent;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.ProvidedCapabilityBuilder;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerSpec;
import picocli.CommandLine;

@CommandLine.Command()
public class EntandoKeycloakServerController implements Runnable {

    public static final String SECRET_KIND = "Secret";
    public static final int KEYCLOAK_DEPLOYMENT_TIME = 300;
    public static final int DATBASE_DEPLOYMENT_TIME = 120;
    private final Logger logger = Logger.getLogger(EntandoKeycloakServerController.class.getName());
    private final KubernetesClientForControllers k8sClient;
    private final CapabilityProvider capabilityProvider;
    private final SimpleKeycloakClient keycloakClient;
    private final DeploymentProcessor deploymentProcessor;
    private static final Collection<Class<? extends EntandoCustomResource>> SUPPORTED_RESOURCE_KINDS = Arrays
            .asList(EntandoKeycloakServer.class, ProvidedCapability.class);
    private EntandoKeycloakServer keycloakServer;
    private ProvidedCapability providedCapability;

    @Inject
    public EntandoKeycloakServerController(KubernetesClientForControllers k8sClient, DeploymentProcessor deploymentProcessor,
            CapabilityProvider capabilityProvider, SimpleKeycloakClient keycloakClient) {
        this.k8sClient = k8sClient;
        this.capabilityProvider = capabilityProvider;
        this.keycloakClient = keycloakClient;
        this.deploymentProcessor = deploymentProcessor;
    }

    @Override
    public void run() {
        EntandoCustomResource resourceToProcess = startProcessingResource();
        try {
            //No need to update the resource being synced to. It will be ignored by ControllerCoordinator
            if (resourceToProcess instanceof EntandoKeycloakServer) {
                //This event originated from the original EntandoDatabaseService, NOT a capability requirement expressed by means of a
                // ProvidedCapability
                //The ProvidedCapability is to be owned by the implementing CustomResource and will therefore be ignored by
                // ControllerCoordinator
                this.keycloakServer = (EntandoKeycloakServer) resourceToProcess;
                this.providedCapability = this.k8sClient.createOrPatchEntandoResource(toCapability(this.keycloakServer));
                validateExternalServiceRequirements(keycloakServer);
            } else {
                //This event originated from the capability requirement, and we need to keep the implementing CustomResource in sync
                //The implementing CustomResource is to be owned by the ProvidedCapability and will therefore be ignored by
                // ControllerCoordinator
                this.providedCapability = (ProvidedCapability) resourceToProcess;
                this.keycloakServer = this.k8sClient.createOrPatchEntandoResource(toKeycloakServer(this.providedCapability));
                validateExternalServiceRequirements(this.providedCapability);
            }
            KeycloakDeployable deployable = new KeycloakDeployable(keycloakServer, databaseServiceFor(keycloakServer), resolveCaSecret());
            KeycloakDeploymentResult result = deploymentProcessor.processDeployable(deployable, KEYCLOAK_DEPLOYMENT_TIME);
            providedCapability = k8sClient.updateStatus(providedCapability, result.getStatus());
            if (!result.getStatus().hasFailed()) {
                if (keycloakServer.getSpec().getProvisioningStrategy().orElse(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY)
                        != CapabilityProvisioningStrategy.USE_EXTERNAL) {
                    ensureHttpAccess(result);
                }
                ensureKeycloakRealm(new ProvidedSsoCapability(capabilityProvider.loadProvisioningResult(providedCapability)));
            }
            keycloakServer = k8sClient.deploymentEnded(keycloakServer);
            providedCapability = k8sClient.deploymentEnded(providedCapability);
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            keycloakServer = k8sClient.deploymentFailed(keycloakServer, e, NameUtils.MAIN_QUALIFIER);
            providedCapability = k8sClient.deploymentFailed(providedCapability, e, NameUtils.MAIN_QUALIFIER);
        }
        keycloakServer.getStatus().findFailedServerStatus().ifPresent(ss -> {
            throw new CommandLine.ExecutionException(new CommandLine(this), ss.getEntandoControllerFailure().getDetailMessage());
        });
    }

    private EntandoCustomResource startProcessingResource() {
        return k8sClient.updatePhase(k8sClient.resolveCustomResourceToProcess(SUPPORTED_RESOURCE_KINDS), EntandoDeploymentPhase.STARTED);
    }

    private void validateExternalServiceRequirements(ProvidedCapability providedCapability) {
        if (providedCapability.getSpec().getProvisioningStrategy().map(CapabilityProvisioningStrategy.USE_EXTERNAL::equals)
                .orElse(false)) {
            final ExternallyProvidedService externallyProvidedService = providedCapability.getSpec().getExternallyProvisionedService()
                    .orElseThrow(() -> new EntandoControllerException(
                            "Please provide the connection information of the SSO service you intend to connect to using the "
                                    + "ProvidedCapability.spec.externallyProvisionedService object."));
            String adminSecretName = ofNullable(externallyProvidedService.getAdminSecretName())
                    .orElseThrow(() -> new EntandoControllerException(
                            "Please provide the name of the secret containing the admin credentials for the SSO service you intend to "
                                    + "connect to using the "
                                    + "ProvidedCapability.spec.externallyProvisionedService.adminSecretName property."));
            if (ofNullable(k8sClient.loadStandardResource(SECRET_KIND, providedCapability.getMetadata().getNamespace(), adminSecretName))
                    .isEmpty()) {
                throw new EntandoControllerException(format(
                        "Please ensure that a secret with the name '%s' exists in the requested namespace %s", adminSecretName,
                        providedCapability.getMetadata().getName()));
            }
            if (ofNullable(externallyProvidedService.getHost()).isEmpty()) {
                throw new EntandoControllerException(
                        "Please provide the hostname of the SSO service you intend to connect to using the "
                                + "ProvidedCapability.spec.externallyProvisionedService.host property.");
            }
        }
    }

    private void validateExternalServiceRequirements(EntandoKeycloakServer entandoKeycloakServer) {
        if (entandoKeycloakServer.getSpec().getProvisioningStrategy().map(CapabilityProvisioningStrategy.USE_EXTERNAL::equals)
                .orElse(false)) {
            if (entandoKeycloakServer.getSpec().getFrontEndUrl().isEmpty()) {
                throw new EntandoControllerException(
                        "Please provide the base URL of the SSO service you intend to connect to using the "
                                + "EntandoKeycloakServer.spec.frontEndUrl property.");
            }
            String adminSecretName = entandoKeycloakServer.getSpec().getAdminSecretName()
                    .orElseThrow(() -> new EntandoControllerException(
                            "Please provide the name of the secret containing the admin credentials for the SSO service you intend to "
                                    + "connect to "
                                    + "using the "
                                    + "EntandoKeycloakServer.spec.adminSecretName property."));
            if (ofNullable(k8sClient.loadStandardResource(SECRET_KIND, entandoKeycloakServer.getMetadata().getNamespace(), adminSecretName))
                    .isEmpty()) {
                throw new EntandoControllerException(format(
                        "Please ensure that a secret with the name '%s' exists in the requested namespace %s", adminSecretName,
                        entandoKeycloakServer.getMetadata().getName()));
            }
        }
    }

    private EntandoKeycloakServer toKeycloakServer(ProvidedCapability providedCapability) {
        final EntandoKeycloakServer keycloakServerWithoutDefaults = new EntandoKeycloakServerBuilder(
                Objects.requireNonNullElseGet(k8sClient
                                .load(EntandoKeycloakServer.class, providedCapability.getMetadata().getNamespace(),
                                        providedCapability.getMetadata().getName()),
                        () -> new EntandoKeycloakServer(new EntandoKeycloakServerSpec())))
                .editMetadata()
                .withLabels(providedCapability.getMetadata().getLabels())
                .withNamespace(providedCapability.getMetadata().getNamespace())
                .withName(providedCapability.getMetadata().getName())
                .endMetadata()
                .editSpec()
                .withProvisioningStrategy(
                        providedCapability.getSpec().getProvisioningStrategy().orElse(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY))
                .withDefault(providedCapability.getSpec().getResolutionScopePreference().contains(CapabilityScope.CLUSTER))
                .withDbms(providedCapability.getSpec().getPreferredDbms().orElse(null))
                .withIngressHostName(providedCapability.getSpec().getPreferredIngressHostName().orElse(null))
                .withDefaultRealm(providedCapability.getSpec().getCapabilityParameters().get(ProvidedSsoCapability.DEFAULT_REALM_PARAMETER))
                .withTlsSecretName(providedCapability.getSpec().getPreferredTlsSecretName().orElse(null))
                .withAdminSecretName(
                        providedCapability.getSpec().getExternallyProvisionedService().map(ExternallyProvidedService::getAdminSecretName)
                                .orElse(null))
                .withFrontEndUrl(providedCapability.getSpec().getExternallyProvisionedService()
                        .map(s -> EntandoKeycloakHelper.deriveFrontEndUrl(providedCapability)).orElse(null)).endSpec().build();

        final EntandoKeycloakServer entandoKeycloakServerWithDefaults = new EntandoKeycloakServerBuilder(keycloakServerWithoutDefaults)
                .editSpec()
                .withDbms(EntandoKeycloakHelper.determineDbmsVendor(keycloakServerWithoutDefaults))
                .withStandardImage(EntandoKeycloakHelper.determineStandardImage(keycloakServerWithoutDefaults))
                .endSpec()
                .build();
        if (!ResourceUtils.customResourceOwns(providedCapability, entandoKeycloakServerWithDefaults)) {
            entandoKeycloakServerWithDefaults.getMetadata().getOwnerReferences().add(ResourceUtils.buildOwnerReference(providedCapability));
        }
        return entandoKeycloakServerWithDefaults;
    }

    private ProvidedCapability toCapability(EntandoKeycloakServer resourceToProcess) {
        ExternallyProvidedService externalService = resourceToProcess.getSpec().getFrontEndUrl()
                .map(ExternalKeycloakService::new)
                .map(s -> new ExternallyProvidedServiceFluent<>().withPort(s.getPort()).withHost(s.getHost())
                        .withPath(s.getPath())
                        .withAdminSecretName(resourceToProcess.getSpec().getAdminSecretName().orElse(null))
                        .build()).orElse(null);

        final ProvidedCapability capabilityToSyncTo = new ProvidedCapabilityBuilder(
                Objects.requireNonNullElseGet(k8sClient.load(ProvidedCapability.class, resourceToProcess.getMetadata().getNamespace(),
                        resourceToProcess.getMetadata().getName()),
                        () -> new ProvidedCapability(new ObjectMeta(), new CapabilityRequirement())))
                .editMetadata()
                .withNamespace(resourceToProcess.getMetadata().getNamespace())
                .withLabels(resourceToProcess.getMetadata().getLabels())
                .withName(resourceToProcess.getMetadata().getName())
                .endMetadata()
                .editSpec()
                .withCapability(StandardCapability.SSO)
                .withImplementation(StandardCapabilityImplementation
                        .valueOf(EntandoKeycloakHelper.determineStandardImage(resourceToProcess).name()))
                .withProvisioningStrategy(
                        resourceToProcess.getSpec().getProvisioningStrategy().orElse(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY))
                .withResolutionScopePreference(
                        resourceToProcess.getSpec().isDefault() ? CapabilityScope.CLUSTER : CapabilityScope.NAMESPACE)
                .withExternallyProvidedService(externalService)
                .withPreferredIngressHostName(resourceToProcess.getSpec().getIngressHostName().orElse(null))
                .withPreferredTlsSecretName(resourceToProcess.getSpec().getTlsSecretName().orElse(null))
                .addAllToCapabilityParameters(resourceToProcess.getSpec().getDefaultRealm()
                        .map(r -> Collections.singletonMap(ProvidedSsoCapability.DEFAULT_REALM_PARAMETER, r))
                        .orElse(Collections.emptyMap()))
                .endSpec().build();
        if (!ResourceUtils.customResourceOwns(resourceToProcess, capabilityToSyncTo)) {
            //If we are here, it means one of two things:
            // 1. This is a new EntandoDatabaseService and we need to create a ProvidedCapability owned by it so that the
            // ControllerCoordinator won't process changes against the ProvidedCapability.
            // 2. the user has removed the ownerReference from the original EntandoDatabaseService, thus indicating
            //that he is taking control of it.  Now we change control over to the original EntandoDatabaseService, make it own the
            // ProvidedCapability so that only its events will be listened to.
            capabilityToSyncTo.getMetadata().getOwnerReferences().add(ResourceUtils.buildOwnerReference(resourceToProcess));
        }
        return capabilityToSyncTo;
    }

    private Secret resolveCaSecret() {
        return EntandoOperatorSpiConfig.getCertificateAuthoritySecretName()
                .map(n -> (Secret) k8sClient.loadStandardResource(SECRET_KIND, k8sClient.getNamespace(), n)).orElse(null);
    }

    private void ensureHttpAccess(KeycloakDeploymentResult serviceDeploymentResult) throws TimeoutException {
        //Give the operator access over http for cluster.local calls
        final ExecutionResult result = k8sClient.executeOnPod(serviceDeploymentResult.getPod(), "server-container", 30,
                "cd \"${KEYCLOAK_HOME}/bin\"",
                "./kcadm.sh config credentials --server http://localhost:8080/auth --realm master "
                        + "--user  \"${KEYCLOAK_USER:-${SSO_ADMIN_USERNAME}}\" "
                        + "--password \"${KEYCLOAK_PASSWORD:-${SSO_ADMIN_PASSWORD}}\"",
                "./kcadm.sh update realms/master -s sslRequired=NONE"
        );
        if (result.hasFailed()) {
            throw new EntandoControllerException("Could not disable Keycloak HTTPS requirement:" + String
                    .join("\n", result.getOutputLines()));
        }
    }

    private DatabaseConnectionInfo databaseServiceFor(EntandoKeycloakServer newEntandoKeycloakServer) throws TimeoutException {
        // Create database for Keycloak
        final DbmsVendor dbmsVendor = EntandoKeycloakHelper.determineDbmsVendor(newEntandoKeycloakServer);
        if (dbmsVendor == DbmsVendor.EMBEDDED) {
            return null;
        } else {
            final CapabilityProvisioningResult databaseCapability = this.capabilityProvider
                    .provideCapability(newEntandoKeycloakServer, new CapabilityRequirementBuilder()
                            .withCapability(StandardCapability.DBMS)
                            .withImplementation(StandardCapabilityImplementation
                                    .valueOf(dbmsVendor.name()))
                            .withResolutionScopePreference(
                                    newEntandoKeycloakServer.getSpec().isDefault() ? CapabilityScope.CLUSTER : CapabilityScope.NAMESPACE)
                            .withProvisioningStrategy(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY)
                            .build(), DATBASE_DEPLOYMENT_TIME);
            databaseCapability.getControllerFailure().ifPresent(f -> {
                throw new EntandoControllerException(databaseCapability.getProvidedCapability(),
                        format("Could not prepare a database for SSO %s/%s:%n%s",
                                newEntandoKeycloakServer.getMetadata().getNamespace(),
                                newEntandoKeycloakServer.getMetadata().getName(),
                                f.getDetailMessage()));
            });
            return new ProvidedDatabaseCapability(databaseCapability);
        }
    }

    private void ensureKeycloakRealm(SsoConnectionInfo ssoConnectionInfo) {
        logger.severe(() -> format("Attempting to log into Keycloak at %s", ssoConnectionInfo.getBaseUrlToUse()));
        keycloakClient.login(ssoConnectionInfo.getBaseUrlToUse(), ssoConnectionInfo.getUsername(),
                ssoConnectionInfo.getPassword());
        keycloakClient.ensureRealm(KeycloakName.ENTANDO_DEFAULT_KEYCLOAK_REALM);
    }

}
