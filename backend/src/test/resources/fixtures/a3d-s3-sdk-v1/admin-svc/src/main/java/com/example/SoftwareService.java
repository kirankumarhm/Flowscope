package com.example;

import org.springframework.beans.factory.annotation.Value;

public class SoftwareService {
    @Value("${aws.buckets.software}")
    private String bucketName;
    private ObjectStore objectStore;

    public void publish(String id, Object stream) {
        this.objectStore.upload(this.bucketName, id, stream);
    }

    public byte[] fetch(String id) {
        return this.objectStore.download(this.bucketName, id);
    }
}
