package com.repeaterx.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiConfig {
    private String host = "0.0.0.0";
    private int port = 7331;

    public ApiConfig() {}

    public ApiConfig(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getBindAddress() {
        return host + ":" + port;
    }
}
