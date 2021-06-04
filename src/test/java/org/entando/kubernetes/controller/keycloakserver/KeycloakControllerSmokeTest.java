package org.entando.kubernetes.controller.keycloakserver;

import static io.qameta.allure.Allure.step;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeoutException;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.support.client.impl.DefaultKeycloakClient;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.model.capability.CapabilityProvisioningStrategy;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.entando.kubernetes.test.e2etest.ControllerExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("smoke")})
@Feature("As an Entando Operator users, I want to use a Docker container to process an EntandoKeycloakServer so that I don't need to "
        + "know any of its implementation details to use it.")
class KeycloakControllerSmokeTest implements FluentIntegrationTesting {

    private static final String MY_NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("my-namespace");
    public static final String MY_KEYCLOAK = EntandoOperatorTestConfig.calculateName("my-keycloak");
    private EntandoKeycloakServer entandoKeycloakServer;

    @Test
    @Description("Should successfully connect to newly deployed Keycloak Server")
    void testDeployment() throws TimeoutException {
        KubernetesClient client = new DefaultKubernetesClient();
        final DefaultSimpleK8SClient simpleClient = new DefaultSimpleK8SClient(client);
        step("Given I have a clean namespace", () -> {
            TestFixturePreparation.prepareTestFixture(client, deleteAll(EntandoKeycloakServer.class).fromNamespace(MY_NAMESPACE));
        });
        step("And I have created an EntandoKeycloakServer custom resource", () -> {
            this.entandoKeycloakServer = simpleClient.entandoResources()
                    .createOrPatchEntandoResource(
                            new EntandoKeycloakServerBuilder()
                                    .withNewMetadata()
                                    .withNamespace(MY_NAMESPACE)
                                    .withName(MY_KEYCLOAK)
                                    .endMetadata()
                                    .withNewSpec()
                                    .withStandardImage(StandardKeycloakImage.KEYCLOAK)
                                    .withDbms(DbmsVendor.EMBEDDED)
                                    .withProvisioningStrategy(CapabilityProvisioningStrategy.DEPLOY_DIRECTLY)
                                    .endSpec()
                                    .build()
                    );
        });
        step("When I run the entando-k8s-keycloak-controller container against the EntandoKeycloakServer", () -> {
            ControllerExecutor executor = new ControllerExecutor(MY_NAMESPACE, simpleClient,
                    r -> "entando-k8s-keycloak-controller");
            executor.runControllerFor(Action.ADDED, entandoKeycloakServer,
                    EntandoOperatorTestConfig.getVersionOfImageUnderTest().orElse("0.0.0-2"));
        });
        step("Then I can successfully login into the newly deployed Keycloak server", () -> {
            final ProvidedCapability capability = simpleClient.entandoResources()
                    .load(ProvidedCapability.class, entandoKeycloakServer.getMetadata().getNamespace(),
                            entandoKeycloakServer.getMetadata().getName());
            final ProvidedSsoCapability ssoCapability = new ProvidedSsoCapability(
                    simpleClient.capabilities().buildCapabilityProvisioningResult(capability));
            final DefaultKeycloakClient keycloakClient = new DefaultKeycloakClient();
            keycloakClient.login(ssoCapability.getExternalBaseUrl(),
                    decode(ssoCapability.getAdminSecret(), SecretUtils.USERNAME_KEY),
                    decode(ssoCapability.getAdminSecret(), SecretUtils.PASSSWORD_KEY));
            keycloakClient.ensureRealm("my-realm");
        });
    }

    private String decode(Secret secret, String usernameKey) {
        return new String(Base64.getDecoder().decode(secret.getData().get(usernameKey)), StandardCharsets.UTF_8);
    }
}
