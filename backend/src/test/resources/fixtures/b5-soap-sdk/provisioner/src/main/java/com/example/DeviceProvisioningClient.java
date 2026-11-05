package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ws.client.core.support.WebServiceGatewaySupport;

public class DeviceProvisioningClient extends WebServiceGatewaySupport {
    @Value("${partnersoap.default-url}")
    private String url;

    public Object provision(Object request) {
        return getWebServiceTemplate().marshalSendAndReceive(url, request);
    }
}
