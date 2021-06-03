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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.qameta.allure.Allure;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.entando.kubernetes.controller.spi.command.SerializationHelper;
import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.capability.CapabilityProvisioningStrategy;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.ExposedServerStatus;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.entando.kubernetes.test.common.SourceLink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure")})
@Feature("As a deployer, I would like to deploy an EntandoKeycloakServer directly so that I have more granular control over the "
        + "configuration settings")
@Issue("ENG-2284")
@SourceLink("DeployedKeycloakServerTest.java")
@SuppressWarnings({"java:S5961"})//because this test is intended to generate documentation and should read like the generated document
class DeployedKeycloakServerTest extends KeycloakTestBase {

    public static final String MY_KEYCLOAK = "my-keycloak";

    @Test
    @Description("Should deploy Red Hat SSO with all the default values in a Red Hat compliant environment")
    void shouldDeployRedHatSsoWithDefaultValues() {
        step("Given that the Entando Operator is running in 'Red Hat' compliance mode",
                () -> attachEnvironmentVariable(EntandoOperatorSpiConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE,
                        EntandoOperatorComplianceMode.REDHAT.getName()));
        step("And the Operator runs in a Kubernetes environment the requires a filesystem user/group override for mounted volumes",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE, "true"));
        step("And the routing suffix has been configured globally ",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_DEFAULT_ROUTING_SUFFIX, "entando.org"));
        theDefaultTlsSecretWasCreatedAndConfiguredAsDefault();
        step("And there is a controller to process requests for the DBMS capability",
                () -> doAnswer(withADatabaseCapabilityStatus(DbmsVendor.POSTGRESQL, "my_db")).when(client.capabilities())
                        .createAndWaitForCapability(argThat(matchesCapability(StandardCapability.DBMS)), anyInt()));
        step("When I create an EntandoKeycloakServer with minimal configuration",
                () -> runControllerAgainstCustomResource(new EntandoKeycloakServerBuilder()
                        .withNewMetadata()
                        .withName(MY_KEYCLOAK)
                        .withNamespace(MY_NAMESPACE)
                        .endMetadata()
                        .withNewSpec()
                        .endSpec()
                        .build()));
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, MY_KEYCLOAK);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, MY_KEYCLOAK);
        step("Then an ProvidedCapability was created for this EntandoKeycloakServer:", () -> {
            attachKubernetesResource("ProvidedCapability", providedCapability);
            step("using the DeployDirectly provisioningStrategy",
                    () -> assertThat(providedCapability.getSpec().getProvisioningStrategy()).contains(
                            CapabilityProvisioningStrategy.DEPLOY_DIRECTLY));
            step("providing the SSO capability",
                    () -> assertThat(providedCapability.getSpec().getCapability()).isEqualTo(StandardCapability.SSO));
            step("and the Red Hat SSO implementation",
                    () -> assertThat(providedCapability.getSpec().getImplementation())
                            .contains(StandardCapabilityImplementation.REDHAT_SSO));
            step("and 'Namespace' provisioning scope was applied'",
                    () -> assertThat(providedCapability.getSpec().getResolutionScopePreference())
                            .contains(CapabilityScope.NAMESPACE));
            step("and it is owned by the EntandoKeycloakServer to ensure only changes from the EntandoKeycloakServer will change the "
                            + "implementing Kubernetes resources",
                    () -> assertThat(ResourceUtils.customResourceOwns(entandoKeycloakServer, providedCapability)));
            final ExposedServerStatus exposedServerStatus = (ExposedServerStatus) providedCapability.getStatus()
                    .findCurrentServerStatus().get();
            step("and the external base url that can be used to connect to this SSO service is available on the status of the "
                            + "ProvidedCapability ",
                    () -> assertThat(exposedServerStatus.getExternalBaseUrl())
                            .isEqualTo("https://" + MY_KEYCLOAK + "-" + MY_NAMESPACE + ".entando.org/auth"));
            step("and the name of the admin secret is available on the status of the ProvidedCapability ",
                    () -> assertThat(exposedServerStatus.getAdminSecretName()).contains("my-keycloak-admin-secret"));
        });
        step("And a PostgreSQL DBMS Capability was provided:", () -> {
            final ProvidedCapability capability = getClient().entandoResources()
                    .load(ProvidedCapability.class, MY_NAMESPACE, "default-postgresql-dbms-in-namespace");
            assertThat(capability)
                    .isNotNull();
            attachKubernetesResource("PostgreSQL DBMS Capability", capability);
        });
        step("And a database schema was prepared for the RedHat SSO service", () -> {
            final Pod dbPreparationPod = getClient().pods().loadPod(MY_NAMESPACE,
                    Map.of("EntandoResourceKind", "EntandoKeycloakServer", "jobKind", "db-preparation-job", "EntandoKeycloakServer",
                            MY_KEYCLOAK));
            assertThat(dbPreparationPod).isNotNull();
            final Container theInitContainer = dbPreparationPod.getSpec().getInitContainers().get(0);
            step("using a cluster local connection to the database Service", () -> {
                assertThat(theVariableNamed("DATABASE_SERVER_HOST").on(theInitContainer))
                        .isEqualTo("default-postgresql-dbms-in-namespace-service.my-namespace.svc.cluster.local");
                assertThat(theVariableNamed("DATABASE_SERVER_PORT").on(theInitContainer))
                        .isEqualTo(String.valueOf(DbmsVendorConfig.POSTGRESQL.getDefaultPort()));
            });
            step("and the admin credentials provided in the PostgreSQL Capability's admin secret", () -> {
                attachKubernetesResource("PostgreSQL Admin Secret",
                        getClient().secrets().loadSecret(providedCapability, "default-postgresql-dbms-in-namespace-admin-secret"));
                assertThat(theVariableReferenceNamed("DATABASE_ADMIN_PASSWORD").on(theInitContainer).getSecretKeyRef().getName())
                        .isEqualTo("default-postgresql-dbms-in-namespace-admin-secret");
                assertThat(theVariableReferenceNamed("DATABASE_ADMIN_USER").on(theInitContainer).getSecretKeyRef().getName())
                        .isEqualTo("default-postgresql-dbms-in-namespace-admin-secret");
                assertThat(theVariableReferenceNamed("DATABASE_ADMIN_PASSWORD").on(theInitContainer).getSecretKeyRef().getKey())
                        .isEqualTo(SecretUtils.PASSSWORD_KEY);
                assertThat(theVariableReferenceNamed("DATABASE_ADMIN_USER").on(theInitContainer).getSecretKeyRef().getKey())
                        .isEqualTo(SecretUtils.USERNAME_KEY);
            });
            step("and the credentials for the  schema user provided in a newly generated secret for the Red Hat SSO deployment: "
                            + "'my-keycloak-db-secret'",
                    () -> {
                        attachKubernetesResource("Schema User Secret",
                                getClient().secrets().loadSecret(providedCapability, "my-keycloak-db-secret"));
                        assertThat(theVariableReferenceNamed("DATABASE_PASSWORD").on(theInitContainer).getSecretKeyRef().getName())
                                .isEqualTo("my-keycloak-db-secret");
                        assertThat(theVariableReferenceNamed("DATABASE_USER").on(theInitContainer).getSecretKeyRef().getName())
                                .isEqualTo("my-keycloak-db-secret");
                        assertThat(theVariableReferenceNamed("DATABASE_PASSWORD").on(theInitContainer).getSecretKeyRef().getKey())
                                .isEqualTo(SecretUtils.PASSSWORD_KEY);
                        assertThat(theVariableReferenceNamed("DATABASE_USER").on(theInitContainer).getSecretKeyRef().getKey())
                                .isEqualTo(SecretUtils.USERNAME_KEY);
                    });
        });

        step("And a Kubernetes Deployment was created reflecting the requirements of the Red Hat SSO container:", () -> {
            final Deployment deployment = client.deployments()
                    .loadDeployment(entandoKeycloakServer, NameUtils.standardDeployment(entandoKeycloakServer));
            attachKubernetesResource("Deployment", deployment);
            step("using the Red Hat SSO Image",
                    () -> assertThat(thePrimaryContainerOn(deployment).getImage()).contains("entando/entando-redhat-sso"));
            step("With a volume mounted to the standard directory /opt/eap/standalone/data",
                    () -> assertThat(theVolumeMountNamed("my-keycloak-server-volume").on(thePrimaryContainerOn(deployment))
                            .getMountPath()).isEqualTo("/opt/eap/standalone/data"));
            step("Which is bound to a PersistentVolumeClain",
                    () -> {
                        final PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                                .loadPersistentVolumeClaim(entandoKeycloakServer, "my-keycloak-server-pvc");
                        attachKubernetesResource("PersistentVolumeClaim", pvc);
                        assertThat(theVolumeNamed("my-keycloak-server-volume").on(deployment).getPersistentVolumeClaim()
                                .getClaimName()).isEqualTo(
                                "my-keycloak-server-pvc");
                    });
            step("And the File System User/Group override " + KeycloakDeployable.REDHAT_SSO_IMAGE_DEFAULT_USERID
                    + "has been applied to the mount", () ->
                    assertThat(deployment.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup())
                            .isEqualTo(KeycloakDeployable.REDHAT_SSO_IMAGE_DEFAULT_USERID));
            step("And has admin credentials resolved from a dynamically provisioned admin secret ",
                    () -> {
                        final Secret secret = client.secrets()
                                .loadSecret(entandoKeycloakServer, NameUtils.standardAdminSecretName(entandoKeycloakServer));
                        attachKubernetesResource("Admin Secret", secret);
                        assertThat(theVariableReferenceNamed("SSO_ADMIN_PASSWORD").on(thePrimaryContainerOn(deployment)).getSecretKeyRef()
                                .getKey())
                                .isEqualTo(SecretUtils.PASSSWORD_KEY);
                        assertThat(theVariableReferenceNamed("SSO_ADMIN_PASSWORD").on(thePrimaryContainerOn(deployment)).getSecretKeyRef()
                                .getName())
                                .isEqualTo(secret.getMetadata().getName());
                        assertThat(theVariableReferenceNamed("SSO_ADMIN_USERNAME").on(thePrimaryContainerOn(deployment)).getSecretKeyRef()
                                .getKey())
                                .isEqualTo(SecretUtils.USERNAME_KEY);
                        assertThat(theVariableReferenceNamed("SSO_ADMIN_USERNAME").on(thePrimaryContainerOn(deployment)).getSecretKeyRef()
                                .getName())
                                .isEqualTo(secret.getMetadata().getName());
                    });
            step("and the credentials for the  schema user provided in a newly generated secret for the Red Hat SSO deployment: "
                            + "'my-keycloak-db-secret'",
                    () -> {

                        attachKubernetesResource("Schema User Secret",
                                getClient().secrets().loadSecret(providedCapability, "my-keycloak-db-secret"));
                        assertThat(
                                theVariableReferenceNamed("DB_PASSWORD").on(thePrimaryContainerOn(deployment)).getSecretKeyRef().getName())
                                .isEqualTo("my-keycloak-db-secret");
                        assertThat(
                                theVariableReferenceNamed("DB_USERNAME").on(thePrimaryContainerOn(deployment)).getSecretKeyRef().getName())
                                .isEqualTo("my-keycloak-db-secret");
                        assertThat(
                                theVariableReferenceNamed("DB_PASSWORD").on(thePrimaryContainerOn(deployment)).getSecretKeyRef().getKey())
                                .isEqualTo(SecretUtils.PASSSWORD_KEY);
                        assertThat(
                                theVariableReferenceNamed("DB_USERNAME").on(thePrimaryContainerOn(deployment)).getSecretKeyRef().getKey())
                                .isEqualTo(SecretUtils.USERNAME_KEY);
                    });
            step("and the connection details for the database service are provided following the standard EAP Container conventions",
                    () -> {
                        assertThat(theVariableNamed("DB_POSTGRESQL_SERVICE_HOST").on(thePrimaryContainerOn(deployment)))
                                .isEqualTo("default-postgresql-dbms-in-namespace-service.my-namespace.svc.cluster.local");
                        assertThat(theVariableNamed("DB_POSTGRESQL_SERVICE_PORT").on(thePrimaryContainerOn(deployment)))
                                .isEqualTo(String.valueOf(DbmsVendorConfig.POSTGRESQL.getDefaultPort()));
                        assertThat(theVariableNamed("DB_SERVICE_PREFIX_MAPPING").on(thePrimaryContainerOn(deployment)))
                                .isEqualTo("db-postgresql=DB");
                    });
        });

        step("And a Kubernetes Service was created:", () -> {
            final Service service = client.services()
                    .loadService(entandoKeycloakServer, NameUtils.standardServiceName(entandoKeycloakServer));
            attachKubernetesResource("Service", service);
            step("Targeting port 8080 in the Deployment", () -> assertThat(service.getSpec().getPorts().get(0).getPort()).isEqualTo(8080));
            step("And with a label selector matching the labels of the Pod Template on the  Deployment",
                    () -> assertThat(service.getSpec().getSelector()).containsAllEntriesOf(
                            Map.of("EntandoResourceKind", "EntandoKeycloakServer", "EntandoKeycloakServer",
                                    entandoKeycloakServer.getMetadata().getName(),
                                    "deployment", entandoKeycloakServer.getMetadata().getName())
                    ));
        });

        step("And a Kubernetes Ingress was created:", () -> {
            final Ingress ingress = client.ingresses()
                    .loadIngress(entandoKeycloakServer.getMetadata().getNamespace(), NameUtils.standardIngressName(entandoKeycloakServer));
            attachKubernetesResource("Ingress", ingress);
            step("With a hostname derived from the Capability name, namespace and the routing suffix", () ->
                    assertThat(ingress.getSpec().getRules().get(0).getHost())
                            .isEqualTo(MY_KEYCLOAK + "-" + MY_NAMESPACE + ".entando.org"));
            step("And the standard path '/auth'", () ->
                    assertThat(ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getPath())
                            .isEqualTo("/auth"));
            step("And with TLS configured to use the default TLS secret", () -> {
                assertThat(ingress.getSpec().getTls().get(0).getHosts())
                        .contains(MY_KEYCLOAK + "-" + MY_NAMESPACE + ".entando.org");
                assertThat(ingress.getSpec().getTls().get(0).getSecretName()).isEqualTo(DEFAULT_TLS_SECRET);
            });
        });

        step("And the default TLS secret was cloned into the Capability's deployment namespace", () -> {
            final Secret secret = client.secrets().loadSecret(providedCapability, DEFAULT_TLS_SECRET);
            attachKubernetesResource("Default TLS Secret", secret);
            assertThat(secret.getType()).isEqualTo("kubernetes.io/tls");

        });
        step("And the resulting SsoConnectionInfo reflects the correct information to connect to the deployed SSO service", () -> {
            SsoConnectionInfo connectionConfig = new ProvidedSsoCapability(
                    getCapabilityProvider().loadProvisioningResult(providedCapability));
            Allure.attachment("SsoConnectionInfo", SerializationHelper.serialize(connectionConfig));
            assertThat(connectionConfig.getExternalBaseUrl()).isEqualTo("https://my-keycloak-my-namespace.entando.org/auth");
            assertThat(connectionConfig.getInternalBaseUrl())
                    .contains("http://my-keycloak-service.my-namespace.svc.cluster.local:8080/auth");
            assertThat(connectionConfig.getUsername()).isEqualTo("entando_keycloak_admin");
            assertThat(connectionConfig.getPassword()).isNotBlank();
        });
        attachKubernetesState();
    }

    @Test
    @Description("Should deploy Keycloak Community Edition using all the configuration options specified in the capability")
    void shouldDeployKeycloakCommunityEditionWithCustomConfigValues() {
        step("Given I have a specific TLS Secret I want to use:", () -> {
            final Secret tlsSecret = new SecretBuilder()
                    .withNewMetadata()
                    .withName("my-tls-secret")
                    .withNamespace(MY_NAMESPACE)
                    .endMetadata()
                    .withType("kubernetes.io/tls")
                    .addToData("tls.crt", "")
                    .addToData("tls.key", "")
                    .build();
            getClient().secrets().createSecretIfAbsent(newResourceRequiringCapability(), tlsSecret);
            attachKubernetesResource("Custom TLS Secret", tlsSecret);
        });
        step("And the routing suffix has been configured globally ",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_DEFAULT_ROUTING_SUFFIX, "entando.org"));
        step("And there is a controller to process requests for the DBMS capability",
                () -> doAnswer(withADatabaseCapabilityStatus(DbmsVendor.MYSQL, "my_db")).when(client.capabilities())
                        .createAndWaitForCapability(argThat(matchesCapability(StandardCapability.DBMS)), anyInt()));

        theDefaultTlsSecretWasCreatedAndConfiguredAsDefault();
        step("When I create an EntandoKeyCloakServer with preferred hostname, TLS secret, DBMS and storageClass specified",
                () -> runControllerAgainstCustomResource(new EntandoKeycloakServerBuilder()
                        .withNewMetadata()
                        .withName(MY_KEYCLOAK)
                        .withNamespace(MY_NAMESPACE)
                        .endMetadata()
                        .withNewSpec()
                        .withDbms(DbmsVendor.MYSQL)
                        .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                        .withStorageClass("my-storage-class")
                        .withIngressHostName("myhost.com")
                        .withTlsSecretName("my-tls-secret")
                        .endSpec()
                        .build()));
        final ProvidedCapability providedCapability = client.entandoResources()
                .load(ProvidedCapability.class, MY_NAMESPACE, MY_KEYCLOAK);
        final EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, MY_KEYCLOAK);
        step("Then a ProvidedCapability was made available:", () -> {
            attachKubernetesResource("ProvidedCapability", providedCapability);
            step("using the DeployDirectly provisioningStrategy",
                    () -> assertThat(providedCapability.getSpec().getProvisioningStrategy()).contains(
                            CapabilityProvisioningStrategy.DEPLOY_DIRECTLY));
            step("and the Keycloak Community implementation",
                    () -> assertThat(providedCapability.getSpec().getImplementation())
                            .contains(StandardCapabilityImplementation.KEYCLOAK));
            step("and the TLS secret previously specified",
                    () -> assertThat(providedCapability.getSpec().getPreferredTlsSecretName()).contains("my-tls-secret"));
            step("and the hostname previously specified",
                    () -> assertThat(providedCapability.getSpec().getPreferredIngressHostName()).contains("myhost.com"));
            final ExposedServerStatus exposedServerStatus = (ExposedServerStatus) providedCapability.getStatus()
                    .findCurrentServerStatus().get();
            step("and the external base url that can be used to connect to this SSO service is available on the status of the "
                            + "ProvidedCapability ",
                    () -> assertThat(exposedServerStatus.getExternalBaseUrl())
                            .isEqualTo("https://myhost.com/auth"));
            step("and the name of the admin secret is available on the status of the ProvidedCapability ",
                    () -> assertThat(exposedServerStatus.getAdminSecretName())
                            .contains("my-keycloak-admin-secret"));
        });
        step("And a Kubernetes Ingress was created:", () -> {
            final Ingress ingress = client.ingresses()
                    .loadIngress(entandoKeycloakServer.getMetadata().getNamespace(), NameUtils.standardIngressName(entandoKeycloakServer));
            attachKubernetesResource("Ingress", ingress);
            step("With the hostname previously specified", () ->
                    assertThat(ingress.getSpec().getRules().get(0).getHost())
                            .isEqualTo("myhost.com"));
            step("And the standard path '/auth'", () ->
                    assertThat(ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).getPath())
                            .isEqualTo("/auth"));
            step("And with TLS configured to use the  TLS secret previously specified", () -> {
                assertThat(ingress.getSpec().getTls().get(0).getHosts())
                        .contains("myhost.com");
                assertThat(ingress.getSpec().getTls().get(0).getSecretName()).isEqualTo("my-tls-secret");
            });
        });
        step("And the resulting SsoConnectionInfo reflects the correct information to connect to the deployed SSO service", () -> {
            SsoConnectionInfo connectionConfig = new ProvidedSsoCapability(
                    getCapabilityProvider().loadProvisioningResult(providedCapability));
            Allure.attachment("SsoConnectionInfo", SerializationHelper.serialize(connectionConfig));
            assertThat(connectionConfig.getExternalBaseUrl()).isEqualTo("https://myhost.com/auth");
            assertThat(connectionConfig.getInternalBaseUrl())
                    .contains("http://" + MY_KEYCLOAK + "-service." + MY_NAMESPACE + ".svc.cluster.local:8080/auth");
            assertThat(connectionConfig.getUsername()).isEqualTo("entando_keycloak_admin");
            assertThat(connectionConfig.getPassword()).isNotBlank();
        });
        attachKubernetesState();
    }

    @Test
    @Description("Should mount the trusted Certificate Authority certificates in the correct location")
    void shouldMountTrustCertificateAuthorityCertificates() {
        step("Given that I have created a CA Cert Secret and configured it as the default for the Entando Operator", () -> {
            final Secret caCertSecret = new SecretBuilder()
                    .withNewMetadata()
                    .withName("my-ca-certs")
                    .withNamespace(MY_NAMESPACE)
                    .endMetadata()
                    .addToData("cert1.crt", Base64.getEncoder().encodeToString("ASDFASDF".getBytes(StandardCharsets.UTF_8)))
                    .addToData("cert2.crt", Base64.getEncoder().encodeToString("ASDFASDF".getBytes(StandardCharsets.UTF_8)))
                    .build();
            client.secrets().overwriteControllerSecret(caCertSecret);
            System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_CA_SECRET_NAME.getJvmSystemProperty(),
                    caCertSecret.getMetadata().getName());
        });
        step("When I create an EntandoKeycloakServer ",
                () -> runControllerAgainstCustomResource(new EntandoKeycloakServerBuilder()
                        .withNewMetadata()
                        .withName(MY_KEYCLOAK)
                        .withNamespace(MY_NAMESPACE)
                        .endMetadata()
                        .withNewSpec()
                        .endSpec()
                        .build()));
        EntandoKeycloakServer entandoKeycloakServer = client.entandoResources()
                .load(EntandoKeycloakServer.class, MY_NAMESPACE, MY_KEYCLOAK);
        step("And a Kubernetes Deployment was created reflecting the requirements of the Red Hat SSO container:", () -> {
            final Deployment deployment = client.deployments()
                    .loadDeployment(entandoKeycloakServer, NameUtils.standardDeployment(entandoKeycloakServer));
            attachKubernetesResource("Deployment", deployment);
            step("With a volume mounted to the certs directory /etc/entando/certs/ ",
                    () -> assertThat(theVolumeMountNamed("my-ca-certs-volume").on(thePrimaryContainerOn(deployment))
                            .getMountPath()).isEqualTo("/etc/entando/certs/my-ca-certs"));
            step("And the volume is bound to the previously configured CA certificate secret",
                    () -> assertThat(theVolumeNamed("my-ca-certs-volume").on(deployment).getSecret().getSecretName())
                            .isEqualTo("my-ca-certs"));
            step("And the mounted files are present in the X509_CA_BUNDLE environment variable",
                    () -> assertThat(theVariableNamed("X509_CA_BUNDLE").on(thePrimaryContainerOn(deployment))).contains(
                            "/etc/entando/certs/my-ca-certs/cert1.crt",
                            "/etc/entando/certs/my-ca-certs/cert2.crt"));
        });
        attachKubernetesState();
    }

}
