package com.example;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

public class Secrets {
    private final SecretsManagerClient client = SecretsManagerClient.create();
}
