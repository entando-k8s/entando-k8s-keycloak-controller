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

import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;

public class EntandoKeycloakHelper {

    private EntandoKeycloakHelper() {

    }

    public static StandardKeycloakImage determineStandardImage(EntandoKeycloakServer entandoKeycloakServer) {
        StandardKeycloakImage standardKeycloakImage;
        if (EntandoOperatorSpiConfig.getComplianceMode() == EntandoOperatorComplianceMode.REDHAT) {
            standardKeycloakImage = StandardKeycloakImage.REDHAT_SSO;
        } else {
            standardKeycloakImage = entandoKeycloakServer.getSpec().getStandardImage().orElse(StandardKeycloakImage.KEYCLOAK);
        }
        return standardKeycloakImage;

    }

    public static DbmsVendor determineDbmsVendor(EntandoKeycloakServer entandoKeycloakServer) {
        DbmsVendor dbmsVendor;
        if (EntandoOperatorSpiConfig.getComplianceMode() == EntandoOperatorComplianceMode.REDHAT) {
            dbmsVendor = entandoKeycloakServer.getSpec().getDbms().orElse(DbmsVendor.POSTGRESQL);
        } else {
            dbmsVendor = entandoKeycloakServer.getSpec().getDbms().orElse(DbmsVendor.EMBEDDED);
        }
        if (dbmsVendor == DbmsVendor.NONE) {
            dbmsVendor = DbmsVendor.EMBEDDED;
        }
        return dbmsVendor;
    }
}
