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

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.ConfigurableResourceContainer;
import org.entando.kubernetes.controller.spi.container.DatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.spi.container.DbAware;
import org.entando.kubernetes.controller.spi.container.DockerImageInfo;
import org.entando.kubernetes.controller.spi.container.IngressingContainer;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.container.PersistentVolumeAware;
import org.entando.kubernetes.controller.spi.container.TlsAware;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.FluentTernary;
import org.entando.kubernetes.controller.support.creators.SecretCreator;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoResourceRequirements;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;

public class KeycloakDeployableContainer implements IngressingContainer, DbAware, TlsAware, PersistentVolumeAware,
        ParameterizableContainer, ConfigurableResourceContainer {

    private static final String COMMUNITY_KEYCLOAK_IMAGE_NAME = "entando/entando-keycloak";
    public static final String REDHAT_SSO_IMAGE_NAME = "entando/entando-redhat-sso";

    private final EntandoKeycloakServer keycloakServer;
    private final DatabaseServiceResult databaseServiceResult;
    private final List<DatabaseSchemaConnectionInfo> databaseSchemaConnectionInfos;

    public KeycloakDeployableContainer(EntandoKeycloakServer keycloakServer, DatabaseServiceResult databaseServiceResult) {
        this.keycloakServer = keycloakServer;
        this.databaseServiceResult = databaseServiceResult;
        databaseSchemaConnectionInfos = Optional.ofNullable(databaseServiceResult)
                .map(databaseServiceResult1 -> DbAware.buildDatabaseSchemaConnectionInfo(keycloakServer,
                        databaseServiceResult, Collections.singletonList("db")))
                .orElse(Collections.emptyList());
    }

    public static String secretName(EntandoKeycloakServer keycloakServer) {
        return keycloakServer.getMetadata().getName() + "-admin-secret";
    }

    @Override
    public DockerImageInfo getDockerImageInfo() {
        return new DockerImageInfo(keycloakServer.getSpec().getCustomImage()
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
        return NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public int getPrimaryPort() {
        return 8080;
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        if (determineStandardKeycloakImage() == StandardKeycloakImage.REDHAT_SSO) {
            vars.add(
                    new EnvVar("SSO_ADMIN_USERNAME", null, SecretUtils.secretKeyRef(secretName(keycloakServer), SecretUtils.USERNAME_KEY)));
            vars.add(new EnvVar("SSO_ADMIN_PASSWORD", null,
                    SecretUtils.secretKeyRef(secretName(keycloakServer), SecretUtils.PASSSWORD_KEY)));
        } else {
            vars.add(new EnvVar("KEYCLOAK_USER", null, SecretUtils.secretKeyRef(secretName(keycloakServer), SecretUtils.USERNAME_KEY)));
            vars.add(
                    new EnvVar("KEYCLOAK_PASSWORD", null, SecretUtils.secretKeyRef(secretName(keycloakServer), SecretUtils.PASSSWORD_KEY)));
        }
        vars.add(new EnvVar("PROXY_ADDRESS_FORWARDING", "true", null));
        return vars;
    }

    @Override
    public List<EnvVar> getDatabaseConnectionVariables() {
        List<EnvVar> vars = new ArrayList<>();
        if (EntandoKeycloakHelper.determineDbmsVendor(keycloakServer) == DbmsVendor.EMBEDDED) {
            vars.add(new EnvVar("DB_VENDOR", "h2", null));
        } else {
            DatabaseSchemaConnectionInfo databaseSchemaConnectionInfo = databaseSchemaConnectionInfos.get(0);
            if (determineStandardKeycloakImage() == StandardKeycloakImage.REDHAT_SSO) {
                String driverName = databaseSchemaConnectionInfo.getVendor().getVendorConfig().getName();
                vars.add(new EnvVar(format("DB_%s_SERVICE_HOST", driverName.toUpperCase(Locale.ROOT)),
                        databaseSchemaConnectionInfo.getInternalServiceHostname(), null));
                vars.add(new EnvVar(format("DB_%s_SERVICE_PORT", driverName.toUpperCase(Locale.ROOT)),
                        databaseSchemaConnectionInfo.getPort(), null));
                vars.add(new EnvVar("DB_SERVICE_PREFIX_MAPPING", format("db-%s=DB", driverName), null));
                vars.add(new EnvVar("DB_USERNAME", null, databaseSchemaConnectionInfo.getUsernameRef()));
            } else {

                vars.add(new EnvVar("DB_ADDR", databaseSchemaConnectionInfo.getInternalServiceHostname(), null));
                vars.add(new EnvVar("DB_PORT", databaseSchemaConnectionInfo.getPort(), null));
                vars.add(new EnvVar("DB_SCHEMA", databaseSchemaConnectionInfo.getSchemaName(), null));
                vars.add(new EnvVar("DB_USER", null, databaseSchemaConnectionInfo.getUsernameRef()));
            }
            vars.add(new EnvVar("DB_VENDOR", determineKeycloaksNonStandardDbVendorName(databaseSchemaConnectionInfo), null));
            vars.add(new EnvVar("DB_DATABASE", databaseSchemaConnectionInfo.getDatabase(), null));
            vars.add(new EnvVar("DB_PASSWORD", null, databaseSchemaConnectionInfo.getPasswordRef()));
            vars.add(new EnvVar("JDBC_PARAMS",
                    databaseServiceResult.getJdbcParameters().entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(
                                    Collectors.joining("&")), null));

        }
        return vars;
    }


    @Override
    public List<DatabaseSchemaConnectionInfo> getSchemaConnectionInfo() {
        return this.databaseSchemaConnectionInfos;
    }

    @Override
    public List<EnvVar> getTlsVariables() {
        List<EnvVar> vars = new ArrayList<>();
        String certFiles = EntandoOperatorConfig.getCertificateAuthorityCertPaths().stream()
                .map(path -> SecretCreator.standardCertPathOf(path.getFileName().toString()))
                .collect(Collectors.joining(" "));
        vars.add(new EnvVar("X509_CA_BUNDLE",
                "/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt /var/run/secrets/kubernetes.io/serviceaccount/ca.crt "
                        + certFiles, null));
        return vars;
    }

    private String determineKeycloaksNonStandardDbVendorName(DatabaseSchemaConnectionInfo databaseSchemaConnectionInfo) {
        return FluentTernary.use("postgres").when(databaseSchemaConnectionInfo.getVendor().getVendorConfig() == DbmsVendorConfig.POSTGRESQL)
                .orElse(databaseSchemaConnectionInfo.getVendor().getVendorConfig().getName());
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
    public String getVolumeMountPath() {
        if (determineStandardKeycloakImage() == StandardKeycloakImage.REDHAT_SSO) {
            return "/opt/eap/standalone/data";
        } else {
            return "/opt/jboss/keycloak/standalone/data";
        }
    }

    @Override
    public Optional<EntandoResourceRequirements> getResourceRequirementsOverride() {
        return keycloakServer.getSpec().getResourceRequirements();
    }

    @Override
    public List<EnvVar> getEnvironmentVariableOverrides() {
        return keycloakServer.getSpec().getEnvironmentVariables();
    }
}
