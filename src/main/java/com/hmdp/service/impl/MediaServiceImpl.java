package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.reliability.MediaStorage;
import com.hmdp.repository.MediaRepository;
import com.hmdp.service.IBusinessOutboxService;
import com.hmdp.service.IMediaService;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class MediaServiceImpl implements IMediaService {

  @Resource
  private MediaRepository mediaRepository;

  private final TransactionTemplate transactions;
  private final IBusinessOutboxService outbox;

  public MediaServiceImpl(TransactionTemplate transactions, IBusinessOutboxService outbox) {
    this.transactions = transactions;
    this.outbox = outbox;
  }

  private SortedSet<String> paths(String images) {
    SortedSet<String> paths = new TreeSet<>();
    for (String path : images.split(",")) {
      if (!path.isEmpty()) {
        paths.add(MediaStorage.normalize(path));
      }
    }
    return paths;
  }

  @Override
  public boolean lockOwnedImages(String images, long user) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Image reference requires a business transaction");
    }
    try {
      // All publishers lock paths in the same order; deletion locks the same owner row.
      for (String path : paths(images)) {
        List<Map<String, Object>> rows = mediaRepository.selectOwnerForUpdate(path);
        if (rows.isEmpty()
            || ((Number) rows.get(0).get("user_id")).longValue() != user
            || !"ACTIVE".equals(rows.get(0).get("state"))) {
          return false;
        }
      }
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  @Override
  public void attach(long blog, String images) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Image reference requires a business transaction");
    }
    for (String path : paths(images)) {
      mediaRepository.insertBlogReference(blog, path);
    }
  }

  @Override
  public Result delete(String input, long user) {
    final String path;
    try {
      path = MediaStorage.normalize(input);
    } catch (IllegalArgumentException invalid) {
      return Result.fail(invalid.getMessage());
    }
    return transactions.execute(
        tx -> {
          List<Map<String, Object>> rows = mediaRepository.selectOwnerForUpdate(path);
          if (rows.isEmpty() || ((Number) rows.get(0).get("user_id")).longValue() != user) {
            return Result.fail("Image not found, or you can't delete it.");
          }
          if (!"ACTIVE".equals(rows.get(0).get("state"))) {
            return Result.ok();
          }
          if (!mediaRepository.selectReferencesForUpdate(path).isEmpty()) {
            return Result.fail("This image is used in a post and can't be deleted.");
          }
          mediaRepository.markDeleting(path);
          outbox.appendMediaDeletionEvent(path, user);
          return Result.ok();
        });
  }

  @Value("${hmdp.upload-dir:" + SystemConstants.IMAGE_UPLOAD_DIR + "}")
  private String uploadDir;

  @Override
  public Result upload(MultipartFile image, long user) {
    try {
      // Get the original file name
      String originalFilename = image.getOriginalFilename();
      if (image.isEmpty()
          || image.getSize() > 5000000
          || originalFilename == null
          || !originalFilename.toLowerCase().matches(".*\\.(jpg|jpeg|png|webp)$")) {
        return Result.fail("Invalid image type or size.");
      }
      byte[] signature = new byte[12];
      try (InputStream stream = image.getInputStream()) {
        if (stream.read(signature) < 12) {
          return Result.fail("Invalid image content.");
        }
      }
      String lower = originalFilename.toLowerCase(Locale.ROOT);
      boolean valid =
          (lower.endsWith(".png")
                  && signature[0] == (byte) 137
                  && signature[1] == 80
                  && signature[2] == 78
                  && signature[3] == 71)
              || ((lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                  && signature[0] == (byte) 255
                  && signature[1] == (byte) 216
                  && signature[2] == (byte) 255)
              || (lower.endsWith(".webp")
                  && new String(signature, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                  && new String(signature, 8, 4, StandardCharsets.US_ASCII).equals("WEBP"));
      if (!valid) {
        return Result.fail("File content doesn't match the image type.");
      }
      // Generate a new file name
      String fileName = createNewFileName(lower);
      // Save the file
      image.transferTo(new File(uploadDir, fileName));
      mediaRepository.insertOwner(fileName, user);
      // Return the result
      log.debug("File uploaded: {}", fileName);
      return Result.ok(fileName);
    } catch (IOException e) {
      throw new RuntimeException("File upload failed", e);
    }
  }

  private String createNewFileName(String originalFilename) {
    // Get the extension
    int dot = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
    String suffix = dot < 0 ? "" : originalFilename.substring(dot + 1);
    // Generate the directories
    String name = UUID.randomUUID().toString();
    int hash = name.hashCode();
    int d1 = hash & 0xF;
    int d2 = (hash >> 4) & 0xF;
    // Check whether the directory exists
    File dir = new File(uploadDir, String.format("/blogs/%d/%d", d1, d2));
    if (!dir.exists()) {
      dir.mkdirs();
    }
    // Generate the file name
    return String.format("/blogs/%d/%d/%s.%s", d1, d2, name, suffix);
  }
}
