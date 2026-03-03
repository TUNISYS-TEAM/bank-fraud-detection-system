package com.fraud.backend.data.service.storage;

import java.io.InputStream;

public interface StorageService {
  String upload(String key, InputStream in, long contentLength, String contentType);
  void delete(String key);
  InputStream download(String key);
}