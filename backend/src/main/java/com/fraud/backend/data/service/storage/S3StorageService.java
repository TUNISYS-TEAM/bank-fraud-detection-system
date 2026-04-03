package com.fraud.backend.data.service.storage;

import java.io.InputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@Service
public class S3StorageService implements StorageService {

  private final S3Client s3;
  private final String bucket;

  public S3StorageService(S3Client s3, @Value("${storage.s3.bucket}") String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public String upload(String key, InputStream in, long contentLength, String contentType) {
    PutObjectRequest req = PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .contentType(contentType)
        .build();

    s3.putObject(req, RequestBody.fromInputStream(in, contentLength));
    return key;
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public InputStream download(String key) {
    ResponseInputStream<GetObjectResponse> obj = s3.getObject(
        GetObjectRequest.builder().bucket(bucket).key(key).build()
    );
    return obj;
  }

  @Override
  public void copy(String sourceKey, String destinationKey) {
    CopyObjectRequest req = CopyObjectRequest.builder()
        .sourceBucket(bucket)
        .sourceKey(sourceKey)
        .destinationBucket(bucket)
        .destinationKey(destinationKey)
        .build();
    s3.copyObject(req);
  }
}