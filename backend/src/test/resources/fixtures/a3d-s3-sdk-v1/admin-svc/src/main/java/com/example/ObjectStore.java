package com.example;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;

public class ObjectStore {
    private AmazonS3 amazonS3;

    public byte[] download(final String bucketName, final String id) {
        final GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, id);
        return read(this.amazonS3.getObject(getObjectRequest));
    }

    public void upload(final String bucketName, final String id, final Object stream) {
        final PutObjectRequest putObjectRequest = new PutObjectRequest(
                bucketName, id, stream, null);
        this.amazonS3.putObject(putObjectRequest);
    }

    private byte[] read(Object o) { return new byte[0]; }
}
