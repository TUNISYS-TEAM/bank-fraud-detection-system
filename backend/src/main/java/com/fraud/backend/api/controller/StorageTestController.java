package com.fraud.backend.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import software.amazon.awssdk.services.s3.S3Client;

@RestController
public class StorageTestController {

  private final S3Client s3Client;

  public StorageTestController(S3Client s3Client) {
    this.s3Client = s3Client;
  }

  @GetMapping("/api/test-minio")
  public String testMinio() {
    s3Client.listBuckets();
    return "MinIO connected successfully";
  }
}