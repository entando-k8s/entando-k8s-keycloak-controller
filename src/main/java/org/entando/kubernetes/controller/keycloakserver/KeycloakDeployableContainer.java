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

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.FluentTernary;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.DockerImageInfo;
import org.entando.kubernetes.controller.creators.SecretCreator;
import org.entando.kubernetes.controller.database.DatabaseSchemaCreationResult;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.database.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.ConfigurableResourceContainer;
import org.entando.kubernetes.controller.spi.DatabasePopulator;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.DefaultDockerImageInfo;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.PersistentVolumeAware;
import org.entando.kubernetes.controller.spi.TlsAware;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoIngressingDeploymentSpec;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;

public class KeycloakDeployableContainer implements IngressingContainer, DbAware, TlsAware, PersistentVolumeAware,
        ParameterizableContainer, ConfigurableResourceContainer {

    private static final String COMMUNITY_KEYCLOAK_IMAGE_NAME = "entando/entando-keycloak";
    public static final String REDHAT_SSO_IMAGE_NAME = "entando/entando-redhat-sso";

    private final EntandoKeycloakServer keycloakServer;
    private final DatabaseServiceResult databaseServiceResult;
    private Map<String, DatabaseSchemaCreationResult> dbSchemas;

    public KeycloakDeployableContainer(EntandoKeycloakServer keycloakServer, DatabaseServiceResult databaseServiceResult) {
        this.keycloakServer = keycloakServer;
        this.databaseServiceResult = databaseServiceResult;
    }

    public static String secretName(EntandoKeycloakServer keycloakServer) {
        return keycloakServer.getMetadata().getName() + "-admin-secret";
    }

    @Override
    public DockerImageInfo getDockerImageInfo() {
        return new DefaultDockerImageInfo(keycloakServer.getSpec().getCustomImage()
                .orElse(determineStandardImageName()));
    }

    private String determineStandardImageName() {
        if (determineStandardKeycloakImage() == StandardKeycloakImage.REDHAT_SSO) {
            return REDHAT_SSO_IMAGE_NAME;
        } else {
            return COMMUNITY_KEYCLOAK_IMAGE_NAME;
        }
    }

    private StandardKeycloakImage determineStandardKeycloakImage() {
        return EntandoKeycloakHelper.determineStandardImage(keycloakServer);
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 768;
    }

    @Override
    public int getCpuLimitMillicores() {
        return 1000;
    }

    @Override
    public String getNameQualifier() {
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public int getPrimaryPort() {
        return 8080;
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("KEYCLOAK_USER", null, KubeUtils.secretKeyRef(secretName(keycloakServer), KubeUtils.USERNAME_KEY)));
        vars.add(new EnvVar("KEYCLOAK_PASSWORD", null, KubeUtils.secretKeyRef(secretName(keycloakServer), KubeUtils.PASSSWORD_KEY)));
        vars.add(new EnvVar("PROXY_ADDRESS_FORWARDING", "true", null));
        return vars;
    }

    @Override
    public List<EnvVar> getDatabaseConnectionVariables() {
        List<EnvVar> vars = new ArrayList<>();
        if (EntandoKeycloakHelper.determineDbmsVendor(keycloakServer) == DbmsVendor.EMBEDDED) {
            if (determineStandardKeycloakImage() == StandardKeycloakImage.REDHAT_SSO) {
                vars.add(new EnvVar("DB_DRIVER", "h2", null));
                vars.add(new EnvVar("DB_JNDI", "java:/redhat-sso-ds", null));
                vars.add(new EnvVar("DATASOURCES", "DB", null));
                vars.add(new EnvVar("DB_SERVICE_HOST", "dummy", null));
                vars.add(new EnvVar("DB_SERVICE_PORT", "1234", null));
            } else {
                vars.add(new EnvVar("DB_VENDOR", "h2", null));
            }
        } else {
            DatabaseSchemaCreationResult databaseSchemaCreationResult = dbSchemas.get("db");
            if (determineStandardKeycloakImage() == StandardKeycloakImage.REDHAT_SSO) {
                vars.add(new EnvVar("DB_SERVICE_HOST", databaseSchemaCreationResult.getInternalServiceHostname(), null));
                vars.add(new EnvVar("DB_SERVICE_PORT", databaseSchemaCreationResult.getPort(), null));
                vars.add(new EnvVar("DB_DRIVER", databaseSchemaCreationResult.getVendor().getName(), null));
                vars.add(new EnvVar("DB_JNDI", "java:jboss/datasources/KeycloakDS", null));
                vars.add(new EnvVar("DB_USERNAME", null, databaseSchemaCreationResult.getUsernameRef()));
                vars.add(new EnvVar("DATASOURCES", "DB", null));
            } else {
                vars.add(new EnvVar("DB_ADDR", databaseSchemaCreationResult.getInternalServiceHostname(), null));
                vars.add(new EnvVar("DB_PORT", databaseSchemaCreationResult.getPort(), null));
                vars.add(new EnvVar("DB_SCHEMA", databaseSchemaCreationResult.getSchemaName(), null));
                vars.add(new EnvVar("DB_USER", null, databaseSchemaCreationResult.getUsernameRef()));
            }
            vars.add(new EnvVar("DB_VENDOR", determineKeycloaksNonStandardDbVendorName(databaseSchemaCreationResult), null));
            vars.add(new EnvVar("DB_DATABASE", databaseSchemaCreationResult.getDatabase(), null));
            vars.add(new EnvVar("DB_PASSWORD", null, databaseSchemaCreationResult.getPasswordRef()));
            vars.add(new EnvVar("JDBC_PARAMS",
                    databaseServiceResult.getJdbcParameters().entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(
                                    Collectors.joining("&")), null));

        }
        return vars;
    }

    @Override
    public List<EnvVar> getTlsVariables() {
        List<EnvVar> vars = new ArrayList<>();
        String certFiles = String.join(" ",
                EntandoOperatorConfig.getCertificateAuthorityCertPaths().stream()
                        .map(path -> SecretCreator.standardCertPathOf(path.getFileName().toString()))
                        .collect(Collectors.toList()));
        vars.add(new EnvVar("X509_CA_BUNDLE",
                "/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt /var/run/secrets/kubernetes.io/serviceaccount/ca.crt "
                        + certFiles, null));
        return vars;
    }

    private String determineKeycloaksNonStandardDbVendorName(DatabaseSchemaCreationResult databaseSchemaCreationResult) {
        return FluentTernary.use("postgres").when(databaseSchemaCreationResult.getVendor().getVendorConfig() == DbmsVendorConfig.POSTGRESQL)
                .orElse(databaseSchemaCreationResult.getVendor().getVendorConfig().getName());
    }

    @Override
    public String getWebContextPath() {
        return "/auth";
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath());
    }

    @Override
    public List<String> getDbSchemaQualifiers() {
        if (EntandoKeycloakHelper.determineDbmsVendor(keycloakServer) == DbmsVendor.EMBEDDED) {
            return Collections.emptyList();
        } else {
            return Arrays.asList("db");
        }
    }

    @Override
    public Optional<DatabasePopulator> useDatabaseSchemas(Map<String, DatabaseSchemaCreationResult> dbSchemas) {
        this.dbSchemas = dbSchemas;
        return Optional.empty();
    }

    @Override
    public String getVolumeMountPath() {
        return "/opt/jboss/keycloak/standalone/data";
    }

    @Override
    public EntandoIngressingDeploymentSpec getCustomResourceSpec() {
        return keycloakServer.getSpec();
    }
}
