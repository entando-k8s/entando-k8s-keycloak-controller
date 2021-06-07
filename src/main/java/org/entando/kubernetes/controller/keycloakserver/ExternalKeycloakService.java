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

import static org.entando.kubernetes.controller.spi.common.ExceptionUtils.ioSafe;

import java.net.URL;
import org.entando.kubernetes.controller.spi.deployable.ExternalService;

public class ExternalKeycloakService implements ExternalService {

    private Integer port;
    private String host;
    private String path;

    public ExternalKeycloakService(String frontEndUrl) {
        URL url = ioSafe(() -> new URL(frontEndUrl));
        this.port = url.getPort();
        if (port == -1) {
            port = url.getDefaultPort();
        }
        this.host = url.getHost();
        this.path = url.getPath();

    }

    @Override
    public String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public boolean getCreateDelegateService() {
        //A delegate service will be a problem for KC 9 out 10 times due to HTTPS and hostname based token validation
        return false;
    }

    public String getPath() {
        return path;
    }
}
