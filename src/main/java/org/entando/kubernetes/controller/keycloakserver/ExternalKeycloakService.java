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

import java.net.URL;
import org.entando.kubernetes.controller.spi.deployable.ExternalService;

public class ExternalKeycloakService implements ExternalService {

    private final URL frontEndUrl;

    public ExternalKeycloakService(URL frontEndUrl) {
        this.frontEndUrl = frontEndUrl;
    }

    @Override
    public String getHost() {
        return frontEndUrl.getHost();
    }

    @Override
    public int getPort() {
        return frontEndUrl.getPort();
    }

    @Override
    public boolean getCreateDelegateService() {
        //A delegate service will be a problem for KC 9 out 10 times due to HTTPS and hostname based token validation
        return false;
    }
}
