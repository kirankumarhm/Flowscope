package com.example;

import com.amazonaws.services.s3.AmazonS3;
import org.springframework.beans.factory.annotation.Value;

public class ExportService {
    @Value("${aws.buckets.exports}")
    private String bucketName;
    private ObjectStore objectStore;

    public void replay(String id) {
        byte[] data = this.objectStore.download(this.bucketName, id);
        this.upload(id, data);
    }

    private void upload(String id, byte[] data) {
    }
}
