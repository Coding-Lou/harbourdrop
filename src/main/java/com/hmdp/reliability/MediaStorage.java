package com.hmdp.reliability;

import com.hmdp.utils.SystemConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaStorage {
  @Value("${hmdp.upload-dir:" + SystemConstants.IMAGE_UPLOAD_DIR + "}")
  private String uploadDir;

  public static String normalize(String path) {
    if (path != null && path.startsWith("/imgs/blogs/")) {
      path = path.substring(5);
    }
    if (path == null || !path.matches("/blogs/[0-9]+/[0-9]+/[a-f0-9-]+\\.(jpg|jpeg|png|webp)")) {
      throw new IllegalArgumentException("Invalid file name.");
    }
    return path;
  }

  public void delete(String path) {
    path = normalize(path);
    Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
    Path file = root.resolve(path.substring(1)).normalize();
    if (!file.startsWith(root) || Files.isDirectory(file)) {
      throw new IllegalStateException("Invalid file path");
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new IllegalStateException("Image deletion failed; will retry", e);
    }
  }
}
