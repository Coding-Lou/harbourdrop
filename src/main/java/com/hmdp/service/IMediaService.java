package com.hmdp.service;

import com.hmdp.dto.Result;
import org.springframework.web.multipart.MultipartFile;

/** Image upload, ownership checks, and reference lifecycle. */
public interface IMediaService {
  boolean lockOwnedImages(String images, long user);

  void attach(long blog, String images);

  Result delete(String input, long user);

  Result upload(MultipartFile image, long user);
}
