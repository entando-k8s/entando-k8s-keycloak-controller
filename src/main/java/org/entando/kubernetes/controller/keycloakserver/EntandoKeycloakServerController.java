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

import io.fabric8.kubernetes.api.model.Secret;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.capability.CapabilityClient;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvisioningResult;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.CommandStream;
import org.entando.kubernetes.controller.spi.command.SerializingDeployCommand;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.spi.container.ProvidedDatabaseCapability;
import org.entando.kubernetes.controller.spi.container.ProvidedKeycloakCapability;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.controller.EntandoControllerException;
import org.entando.kubernetes.model.capability.CapabilityProvisioningStrategy;
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
import org.entando.kubernetes.model.keycloakserver.NestedEntandoKeycloakServerSpecFluent;
import picocli.CommandLine;

@CommandLine.Command()
public class EntandoKeycloakServerController implements Runnable {

    public static final String SECRET_KIND = "Secret";
    private final Logger logger = Logger.getLogger(EntandoKeycloakServerController.class.getName());
    private final KubernetesClientForControllers k8sClient;
    private final CapabilityProvider capabilityProvider;
    private final SimpleKeycloakClient keycloakClient;
    private final SerializingDeployCommand serializingDeployCommand;
    private static final Collection<Class<? extends EntandoCustomResource>> SUPPORTED_RESOURCE_KINDS = Arrays
            .asList(EntandoKeycloakServer.class, ProvidedCapability.class);
    private EntandoKeycloakServer entandoKeycloakServer;
    private ProvidedCapability providedCapability;

    @Inject
    public EntandoKeycloakServerController(KubernetesClientForControllers k8sClient, CapabilityClient capabilityClient,
            CommandStream commandStream, SimpleKeycloakClient keycloakClient) {
        this.k8sClient = k8sClient;
        this.capabilityProvider = new CapabilityProvider(capabilityClient);
        this.keycloakClient = keycloakClient;
        this.serializingDeployCommand = new SerializingDeployCommand(k8sClient, commandStream);
    }

    @Override
    public void run() {
        EntandoCustomResource resourceToProcess = k8sClient.resolveCustomResourceToProcess(SUPPORTED_RESOURCE_KINDS);
        k8sClient.updatePhase(resourceToProcess, EntandoDeploymentPhase.STARTED);
        try {
            //No need to update the resource being synced to. It will be ignored by ControllerCoordinator
            if (resourceToProcess instanceof EntandoKeycloakServer) {
                //This event originated from the original EntandoDatabaseService, NOT a capability requirement expressed by means of a
                // ProvidedCapability
                //The ProvidedCapability is to be owned by the implementing CustomResource and will therefore be ignored by
                // ControllerCoordinator
                this.entandoKeycloakServer = (EntandoKeycloakServer) resourceToProcess;
                this.providedCapability = syncFromImplementingResourceToCapability(this.entandoKeycloakServer);
                this.k8sClient.createOrPatchEntandoResource(this.providedCapability);
                validateExternalServiceRequirements(entandoKeycloakServer);
            } else {
                //This event originated from the capability requirement, and we need to keep the implementing CustomResource in sync
                //The implementing CustomResource is to be owned by the ProvidedCapability and will therefore be ignored by
                // ControllerCoordinator
                this.providedCapability = (ProvidedCapability) resourceToProcess;
                this.entandoKeycloakServer = syncFromCapabilityToImplementingCustomResource(this.providedCapability);
                this.k8sClient.createOrPatchEntandoResource(this.entandoKeycloakServer);
                validateExternalServiceRequirements(this.providedCapability);
            }
            KeycloakDeployable deployable = new KeycloakDeployable(entandoKeycloakServer,
                    prepareKeycloakDatabaseService(entandoKeycloakServer), resolveCaSecret());
            KeycloakDeploymentResult result = serializingDeployCommand.processDeployable(deployable);
            k8sClient.updateStatus(entandoKeycloakServer, result.getStatus());
            k8sClient.updateStatus(providedCapability, result.getStatus());
            if (entandoKeycloakServer.getSpec().getProvisioningStrategy().orElse(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY)
                    != CapabilityProvisioningStrategy.USE_EXTERNAL) {
                ensureHttpAccess(result);
            }
            ensureKeycloakRealm(new ProvidedKeycloakCapability(capabilityProvider.loadProvisioningResult(providedCapability)));
            k8sClient.updatePhase(entandoKeycloakServer, EntandoDeploymentPhase.SUCCESSFUL);
            k8sClient.updatePhase(providedCapability, EntandoDeploymentPhase.SUCCESSFUL);
        } catch (Exception e) {
            k8sClient.deploymentFailed(entandoKeycloakServer, e);
            k8sClient.deploymentFailed(providedCapability, e);
            throw new CommandLine.ExecutionException(new CommandLine(this), e.getMessage(), e);
        }
    }

    private void validateExternalServiceRequirements(ProvidedCapability providedCapability) {
        if (providedCapability.getSpec().getProvisioningStrategy().map(CapabilityProvisioningStrategy.USE_EXTERNAL::equals)
                .orElse(false)) {
            final ExternallyProvidedService externallyProvidedService = providedCapability.getSpec().getExternallyProvisionedService()
                    .orElseThrow(() -> new EntandoControllerException(
                            "Please provide the connection information of the SSO server you intend to connect to using the "
                                    + "ProvidedCapability.spec.externallyProvisionedService object."));
            String adminSecretName = ofNullable(externallyProvidedService.getAdminSecretName())
                    .orElseThrow(() -> new EntandoControllerException(
                            "Please provide the name of the secret containing the admin credentials server you intend to connect to "
                                    + "using the "
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
                        "Please provide the base URL of the SSO server you intend to connect to using the "
                                + "EntandoKeycloakServer.spec.frontEndUrl property.");
            }
            String adminSecretName = entandoKeycloakServer.getSpec().getAdminSecretName()
                    .orElseThrow(() -> new EntandoControllerException(
                            "Please provide the name of the secret containing the admin credentials server you intend to connect to "
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

    private EntandoKeycloakServer syncFromCapabilityToImplementingCustomResource(ProvidedCapability providedCapability) {
        final Map<String, String> capabilityParameters = ofNullable(providedCapability.getSpec().getCapabilityParameters()).orElse(
                Collections.emptyMap());
        final NestedEntandoKeycloakServerSpecFluent<EntandoKeycloakServerBuilder> specFluent = new EntandoKeycloakServerBuilder()
                .withNewMetadata()
                .withLabels(providedCapability.getMetadata().getLabels())
                .withNamespace(providedCapability.getMetadata().getNamespace())
                .withName(providedCapability.getMetadata().getName())
                .endMetadata()
                .withNewSpec()
                .withProvisioningStrategy(
                        providedCapability.getSpec().getProvisioningStrategy().orElse(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY))
                .withDefault(providedCapability.getSpec().getScope().orElse(CapabilityScope.NAMESPACE) == CapabilityScope.CLUSTER)
                .withDbms(ofNullable(capabilityParameters.get("dbms"))
                        .map(s -> DbmsVendor.valueOf(s.toUpperCase(Locale.ROOT))).orElse(null))
                .withIngressHostName(providedCapability.getSpec().getPreferredHostName().orElse(null))
                .withTlsSecretName(providedCapability.getSpec().getPreferredTlsSecretName().orElse(null));

        EntandoKeycloakServer basicEntandoKeycloakServer = providedCapability.getSpec().getExternallyProvisionedService().map(s ->
                specFluent.withAdminSecretName(s.getAdminSecretName())
                        .withFrontEndUrl(EntandoKeycloakHelper.deriveFrontEndUrl(providedCapability))

        ).orElse(specFluent).endSpec().build();

        final EntandoKeycloakServer entandoKeycloakServerWithDefaults = new EntandoKeycloakServerBuilder(basicEntandoKeycloakServer)
                .editSpec()
                .withDbms(EntandoKeycloakHelper.determineDbmsVendor(basicEntandoKeycloakServer))
                .withStandardImage(EntandoKeycloakHelper.determineStandardImage(basicEntandoKeycloakServer))
                .endSpec()
                .build();
        if (!ResourceUtils.customResourceOwns(providedCapability, entandoKeycloakServerWithDefaults)) {
            entandoKeycloakServerWithDefaults.getMetadata().getOwnerReferences().add(ResourceUtils.buildOwnerReference(providedCapability));
        }
        return entandoKeycloakServerWithDefaults;
    }

    private ProvidedCapability syncFromImplementingResourceToCapability(EntandoKeycloakServer resourceToProcess) {
        ExternallyProvidedService externalService = resourceToProcess.getSpec().getFrontEndUrl()
                .map(ExternalKeycloakService::new)
                .map(s -> new ExternallyProvidedServiceFluent<>().withPort(s.getPort()).withHost(s.getHost())
                        .withPath(s.getPath())
                        .withAdminSecretName(resourceToProcess.getSpec().getAdminSecretName().orElse(null))
                        .build()).orElse(null);
        ProvidedCapabilityBuilder builder = new ProvidedCapabilityBuilder().withNewMetadata()
                .withNamespace(resourceToProcess.getMetadata().getNamespace())
                .withName(resourceToProcess.getMetadata().getName()).endMetadata()
                .withNewSpec()
                .withCapability(StandardCapability.SSO)
                .withImplementation(StandardCapabilityImplementation
                        .valueOf(EntandoKeycloakHelper.determineStandardImage(resourceToProcess).name()))
                .withProvisioningStrategy(
                        resourceToProcess.getSpec().getProvisioningStrategy().orElse(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY))
                .withCapabilityRequirementScope(
                        resourceToProcess.getSpec().isDefault() ? CapabilityScope.CLUSTER : CapabilityScope.NAMESPACE)
                .withExternallyProvidedService(externalService)
                .withPreferredHostName(resourceToProcess.getSpec().getIngressHostName().orElse(null))
                .withPreferredTlsSecretName(resourceToProcess.getSpec().getTlsSecretName().orElse(null))
                .endSpec();

        final ProvidedCapability capabilityToSyncTo = builder.build();
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

    private void ensureHttpAccess(KeycloakDeploymentResult serviceDeploymentResult) {
        //Give the operator access over http for cluster.local calls
        k8sClient.executeOnPod(serviceDeploymentResult.getPod(), "server-container", 30,
                "cd \"${KEYCLOAK_HOME}/bin\"",
                "./kcadm.sh config credentials --server http://localhost:8080/auth --realm master "
                        + "--user  \"${KEYCLOAK_USER:-${SSO_ADMIN_USERNAME}}\" "
                        + "--password \"${KEYCLOAK_PASSWORD:-${SSO_ADMIN_PASSWORD}}\"",
                "./kcadm.sh update realms/master -s sslRequired=NONE"
        );
    }

    private DatabaseServiceResult prepareKeycloakDatabaseService(EntandoKeycloakServer newEntandoKeycloakServer) {
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
                            .withCapabilityRequirementScope(
                                    newEntandoKeycloakServer.getSpec().isDefault() ? CapabilityScope.DEDICATED : CapabilityScope.NAMESPACE)
                            .withProvisioningStrategy(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY)
                            .build());
            return new ProvidedDatabaseCapability(databaseCapability);
        }
    }

    private void ensureKeycloakRealm(KeycloakConnectionConfig keycloakConnectionConfig) {
        logger.severe(() -> format("Attempting to log into Keycloak at %s", keycloakConnectionConfig.determineBaseUrl()));
        keycloakClient.login(keycloakConnectionConfig.determineBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        keycloakClient.ensureRealm(KeycloakName.ENTANDO_DEFAULT_KEYCLOAK_REALM);
    }

}
