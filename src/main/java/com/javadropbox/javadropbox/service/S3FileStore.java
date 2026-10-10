package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A {@link FileStore} in an S3 bucket, or a bucket of any service with the same API, such as MinIO,
 * Cloudflare R2 or Backblaze B2. Each key is an object key below an optional prefix, laid out as
 * the serving directory is ({@code .users/<id>/...}, {@code .versions/<id>/v<n>}), so a serving
 * directory copied into a bucket is a store this reads as it is.
 *
 * <p>S3 has objects rather than files and folders, so this follows the convention S3's own console
 * and tools share: a folder is there while anything is stored below its key and a slash, and an
 * empty one is an empty object named with the slash, a folder marker. Folders the app creates get
 * one, and so does the folder that held something deleted, so that it stays as it would on a disk.
 * Where an object and a folder share a name, which only another tool could make, the object is the
 * item and the folder is not there.
 *
 * <p>Nothing is renamed in S3: a move is a copy within S3 followed by a delete. Readers still see
 * the old content or the new, never part of either, but an interruption between the two leaves
 * both; the leftovers that matter, scratch files and versions, are what {@link StorageSweeper}
 * cleans up. Every object S3 writes is new, so a moved file is never older than its move, which is
 * all {@link #touch} is for.
 *
 * <p>Content is sent on to S3 as it arrives and read from it as it is sent, so neither ever has to
 * fit in memory: large uploads go up in parts, and downloads ask S3 for only the range a client
 * asked for.
 */
public class S3FileStore implements FileStore, AutoCloseable {

  /** The longest key S3 accepts, in bytes of UTF-8. */
  static final int MAX_KEY_BYTES = 1024;

  // Above this an upload goes up in parts, and a copy is made in parts. S3 takes 5 GiB in one go,
  // but a part that fails can be sent again on its own.
  static final long DEFAULT_PART_SIZE = 64L * 1024 * 1024;
  private static final long MAX_SINGLE_COPY = 5L * 1024 * 1024 * 1024;
  private static final int MAX_PARTS = 10_000;
  private static final int MAX_DELETES_PER_REQUEST = 1000;

  private static final Logger log = LoggerFactory.getLogger(S3FileStore.class);

  private final S3Client client;
  private final String bucket;
  private final String prefix;
  private final long partSize;

  /**
   * Checks that the bucket can be reached, and puts the welcome file in a store that is empty.
   *
   * @param prefix where the store's keys start in the bucket; empty for the whole bucket
   * @throws IllegalStateException if the bucket cannot be reached, saying why
   */
  public S3FileStore(S3Client client, String bucket, String prefix) {
    this(client, bucket, prefix, DEFAULT_PART_SIZE);
  }

  // For tests, with parts small enough to send a few of.
  S3FileStore(S3Client client, String bucket, String prefix, long partSize) {
    this.client = client;
    this.bucket = bucket;
    this.prefix = normalizePrefix(prefix);
    this.partSize = partSize;
    check();
    try {
      if (client
          .listObjectsV2(request -> request.bucket(bucket).prefix(this.prefix).maxKeys(1))
          .contents()
          .isEmpty()) {
        client.putObject(
            request -> request.bucket(bucket).key(objectKey(StoragePaths.WELCOME_FILE)),
            RequestBody.fromString(StoragePaths.WELCOME_TEXT, StandardCharsets.UTF_8));
        log.info("Created {} in {}", StoragePaths.WELCOME_FILE, description());
      }
    } catch (SdkException e) {
      throw new IllegalStateException("Could not use " + description() + ": " + reason(e), e);
    }
  }

  // "files", "/files/" and "files/" are all the folder "files/".
  private static String normalizePrefix(String prefix) {
    String trimmed = prefix.strip();
    while (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isEmpty() ? "" : trimmed + "/";
  }

  /**
   * Checks that the bucket can be reached with the credentials given.
   *
   * @throws IllegalStateException if it cannot, saying why
   */
  public void check() {
    try {
      client.headBucket(request -> request.bucket(bucket));
    } catch (NoSuchBucketException e) {
      throw new IllegalStateException("The bucket \"" + bucket + "\" does not exist", e);
    } catch (S3Exception e) {
      if (e.statusCode() == 403) {
        throw new IllegalStateException(
            "Not allowed to use the bucket \""
                + bucket
                + "\": check the credentials and their permissions",
            e);
      }
      throw new IllegalStateException(
          "Could not reach the bucket \"" + bucket + "\": " + reason(e), e);
    } catch (SdkException e) {
      throw new IllegalStateException(
          "Could not reach the bucket \"" + bucket + "\": " + reason(e), e);
    }
  }

  @Override
  public String description() {
    return "the bucket s3://" + bucket + "/" + prefix;
  }

  @Override
  public void close() {
    client.close();
  }

  // S3 keys are spelled exactly as given and cannot be links, so there is nothing to check.
  @Override
  public String spelling(String base, String relative) {
    return relative;
  }

  @Override
  public Optional<Entry> stat(String key) throws IOException {
    if (key.isEmpty()) {
      return Optional.of(folder("", null));
    }
    try {
      HeadObjectResponse head =
          client.headObject(request -> request.bucket(bucket).key(objectKey(key)));
      return Optional.of(file(key, head.contentLength(), head.lastModified()));
    } catch (NoSuchKeyException e) {
      // Not a file; it may be a folder.
    } catch (SdkException e) {
      if (!isNotFound(e)) {
        throw failure("look at", key, e);
      }
    }
    return firstBelow(key)
        .map(first -> folder(key, isMarkerOf(first, key) ? first.lastModified() : null));
  }

  // The first object below the folder at key: its marker, if it has one.
  private Optional<S3Object> firstBelow(String key) throws IOException {
    try {
      return client
          .listObjectsV2(request -> request.bucket(bucket).prefix(folderPrefix(key)).maxKeys(1))
          .contents()
          .stream()
          .findFirst();
    } catch (SdkException e) {
      throw failure("list", key, e);
    }
  }

  private boolean isMarkerOf(S3Object object, String folder) {
    return object.key().equals(folderPrefix(folder));
  }

  /**
   * One listing of everything below the folder, a thousand objects a request. S3 lists keys in
   * order, so everything below a folder comes together, right after the folder's marker if it has
   * one; a folder known only from what is in it is visited when the first of that comes up.
   */
  @Override
  public void walk(String key, Visitor visitor) throws IOException {
    Optional<Entry> start = stat(key);
    if (start.isEmpty()) {
      return;
    }
    FileVisitResult first = visitor.visit(start.get());
    if (!start.get().isDirectory() || first != FileVisitResult.CONTINUE) {
      return;
    }
    String below = folderPrefix(key);
    // The folders being visited, innermost last, and one whose contents are left out.
    Deque<String> open = new ArrayDeque<>();
    String skipped = null;
    try {
      for (S3Object object : listAll(below)) {
        String objectKey = object.key();
        boolean isMarker = objectKey.endsWith("/");
        String itemKey =
            keyOf(isMarker ? objectKey.substring(0, objectKey.length() - 1) : objectKey);
        if (objectKey.equals(below) || !isItemKey(itemKey, key)) {
          continue;
        }
        if (skipped != null && (itemKey.equals(skipped) || itemKey.startsWith(skipped + "/"))) {
          continue;
        }
        skipped = null;
        while (!open.isEmpty() && !itemKey.startsWith(open.peekLast() + "/")) {
          open.removeLast();
        }
        // The folders on the way to it that nothing has visited yet: ones with no marker.
        String folder = open.isEmpty() ? key : open.peekLast();
        String rest = itemKey.substring(folder.isEmpty() ? 0 : folder.length() + 1);
        for (int slash = rest.indexOf('/');
            slash >= 0 && skipped == null;
            slash = rest.indexOf('/')) {
          folder = child(folder, rest.substring(0, slash));
          rest = rest.substring(slash + 1);
          FileVisitResult result = visitor.visit(folder(folder, null));
          if (result == FileVisitResult.TERMINATE) {
            return;
          }
          if (result == FileVisitResult.SKIP_SUBTREE) {
            skipped = folder;
          } else {
            open.addLast(folder);
          }
        }
        if (skipped != null) {
          continue;
        }
        if (isMarker) {
          FileVisitResult result = visitor.visit(folder(itemKey, object.lastModified()));
          if (result == FileVisitResult.TERMINATE) {
            return;
          }
          if (result == FileVisitResult.SKIP_SUBTREE) {
            skipped = itemKey;
          } else {
            open.addLast(itemKey);
          }
        } else {
          if (visitor.visit(file(itemKey, object.size(), object.lastModified()))
              == FileVisitResult.TERMINATE) {
            return;
          }
          // A folder by the same name, which comes right after, is hidden by the file.
          skipped = itemKey;
        }
      }
    } catch (SdkException e) {
      throw failure("list", key, e);
    }
  }

  @Override
  public List<Entry> list(String key) throws IOException {
    String below = folderPrefix(key);
    List<Entry> files = new ArrayList<>();
    List<Entry> folders = new ArrayList<>();
    boolean found = key.isEmpty();
    try {
      for (ListObjectsV2Response page :
          client.listObjectsV2Paginator(
              request -> request.bucket(bucket).prefix(below).delimiter("/"))) {
        for (S3Object object : page.contents()) {
          found = true;
          String itemKey = keyOf(object.key());
          if (!object.key().equals(below) && isItemKey(itemKey, key)) {
            files.add(file(itemKey, object.size(), object.lastModified()));
          }
        }
        for (CommonPrefix folder : page.commonPrefixes()) {
          found = true;
          String folderKey = keyOf(folder.prefix().substring(0, folder.prefix().length() - 1));
          if (isItemKey(folderKey, key)) {
            folders.add(folder(folderKey, null));
          }
        }
      }
    } catch (SdkException e) {
      throw failure("list", key, e);
    }
    if (!found) {
      throw new NoSuchFileException(key);
    }
    Set<String> fileKeys = new HashSet<>();
    files.forEach(file -> fileKeys.add(file.key()));
    folders.removeIf(folder -> fileKeys.contains(folder.key()));
    files.addAll(folders);
    return files;
  }

  @Override
  public InputStream open(String key) throws IOException {
    try {
      return new ObjectContent(
          client.getObject(request -> request.bucket(bucket).key(objectKey(key))));
    } catch (SdkException e) {
      throw failure("read", key, e);
    }
  }

  @Override
  public Resource resource(Entry file) {
    return new ObjectResource(file);
  }

  @Override
  public <T> T readLocally(String key, LocalReader<T> reader) throws IOException {
    Path copy = Files.createTempFile("javadropbox-", ".tmp");
    try {
      try (InputStream in = open(key)) {
        Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
      }
      return reader.read(copy);
    } finally {
      Files.deleteIfExists(copy);
    }
  }

  // Nothing is created until it is written: an object only appears once it is whole.
  @Override
  public String scratchBeside(String key) {
    return child(parentOf(key), ".upload-" + UUID.randomUUID() + ".tmp");
  }

  @Override
  public long write(String key, InputStream content, long length) throws IOException {
    if (length < 0) {
      // S3 has to be told the size of what it is sent, so find out first.
      Path spooled = Files.createTempFile("javadropbox-", ".tmp");
      try {
        long size = Files.copy(content, spooled, StandardCopyOption.REPLACE_EXISTING);
        try (InputStream in = Files.newInputStream(spooled)) {
          return write(key, in, size);
        }
      } finally {
        Files.deleteIfExists(spooled);
      }
    }
    String objectKey = objectKey(key);
    try {
      if (length <= partSize) {
        client.putObject(
            request -> request.bucket(bucket).key(objectKey),
            RequestBody.fromInputStream(new Unclosed(content, length), length));
      } else {
        writeInParts(objectKey, content, length);
      }
    } catch (SdkException e) {
      throw failure("write", key, e);
    }
    return length;
  }

  private void writeInParts(String objectKey, InputStream content, long length) {
    long size = Math.max(partSize, (length + MAX_PARTS - 1) / MAX_PARTS);
    String upload =
        client.createMultipartUpload(request -> request.bucket(bucket).key(objectKey)).uploadId();
    try {
      List<CompletedPart> parts = new ArrayList<>();
      for (long offset = 0; offset < length; offset += size) {
        int number = parts.size() + 1;
        long partLength = Math.min(size, length - offset);
        String etag =
            client
                .uploadPart(
                    request ->
                        request.bucket(bucket).key(objectKey).uploadId(upload).partNumber(number),
                    RequestBody.fromInputStream(new Unclosed(content, partLength), partLength))
                .eTag();
        parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
      }
      client.completeMultipartUpload(
          request ->
              request
                  .bucket(bucket)
                  .key(objectKey)
                  .uploadId(upload)
                  .multipartUpload(completed -> completed.parts(parts)));
    } catch (RuntimeException e) {
      abort(objectKey, upload);
      throw e;
    }
  }

  private void abort(String objectKey, String upload) {
    try {
      client.abortMultipartUpload(
          request -> request.bucket(bucket).key(objectKey).uploadId(upload));
    } catch (SdkException e) {
      log.warn("Could not abort the upload of {}; the bucket keeps its parts", objectKey, e);
    }
  }

  @Override
  public void copy(String from, String to) throws IOException {
    try {
      copyObject(objectKey(from), objectKey(to));
    } catch (SdkException e) {
      throw failure("copy", from, e);
    }
  }

  private void copyObject(String from, String to) {
    try {
      client.copyObject(
          request ->
              request
                  .sourceBucket(bucket)
                  .sourceKey(from)
                  .destinationBucket(bucket)
                  .destinationKey(to));
    } catch (S3Exception e) {
      // One request copies at most 5 GiB; a larger object has to be copied in parts.
      if (e.statusCode() != 400) {
        throw e;
      }
      long size = client.headObject(request -> request.bucket(bucket).key(from)).contentLength();
      if (size <= MAX_SINGLE_COPY) {
        throw e;
      }
      copyInParts(from, to, size);
    }
  }

  private void copyInParts(String from, String to, long length) {
    long size = Math.max(partSize, (length + MAX_PARTS - 1) / MAX_PARTS);
    String upload =
        client.createMultipartUpload(request -> request.bucket(bucket).key(to)).uploadId();
    try {
      List<CompletedPart> parts = new ArrayList<>();
      for (long offset = 0; offset < length; offset += size) {
        int number = parts.size() + 1;
        String range = "bytes=" + offset + "-" + (Math.min(offset + size, length) - 1);
        String etag =
            client
                .uploadPartCopy(
                    request ->
                        request
                            .sourceBucket(bucket)
                            .sourceKey(from)
                            .destinationBucket(bucket)
                            .destinationKey(to)
                            .uploadId(upload)
                            .partNumber(number)
                            .copySourceRange(range))
                .copyPartResult()
                .eTag();
        parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
      }
      client.completeMultipartUpload(
          request ->
              request
                  .bucket(bucket)
                  .key(to)
                  .uploadId(upload)
                  .multipartUpload(completed -> completed.parts(parts)));
    } catch (RuntimeException e) {
      abort(to, upload);
      throw e;
    }
  }

  @Override
  public void move(String from, String to) throws IOException {
    if (exists(to)) {
      throw new FileAlreadyExistsException(to);
    }
    Entry source = stat(from).orElseThrow(() -> new NoSuchFileException(from));
    try {
      if (!source.isDirectory()) {
        copyObject(objectKey(from), objectKey(to));
        deleteObjects(List.of(objectKey(from)));
        return;
      }
      String fromPrefix = folderPrefix(from);
      String toPrefix = folderPrefix(to);
      List<String> moved = new ArrayList<>();
      for (S3Object object : listAll(fromPrefix)) {
        copyObject(object.key(), toPrefix + object.key().substring(fromPrefix.length()));
        moved.add(object.key());
      }
      deleteObjects(moved);
    } catch (SdkException e) {
      throw failure("move", from, e);
    }
  }

  @Override
  public void replace(String from, String to) throws IOException {
    try {
      copyObject(objectKey(from), objectKey(to));
      deleteObjects(List.of(objectKey(from)));
    } catch (SdkException e) {
      throw failure("move", from, e);
    }
  }

  // Every object S3 writes, copies included, is new.
  @Override
  public void touch(String key) {}

  @Override
  public boolean createFile(String key) throws IOException {
    if (firstBelow(key).isPresent()) {
      return false;
    }
    try {
      // Only if no object has the name, even one being written at the same moment.
      client.putObject(
          request -> request.bucket(bucket).key(objectKey(key)).ifNoneMatch("*"),
          RequestBody.empty());
      return true;
    } catch (SdkException e) {
      if (isTaken(e)) {
        return false;
      }
      throw failure("create", key, e);
    }
  }

  @Override
  public void createFolder(String key) throws IOException {
    if (exists(key)) {
      throw new FileAlreadyExistsException(key);
    }
    try {
      client.putObject(
          request -> request.bucket(bucket).key(folderPrefix(key)).ifNoneMatch("*"),
          RequestBody.empty());
    } catch (SdkException e) {
      if (isTaken(e)) {
        throw new FileAlreadyExistsException(key);
      }
      throw failure("create", key, e);
    }
  }

  @Override
  public void createFolders(String key) throws IOException {
    Deque<String> missing = new ArrayDeque<>();
    for (String folder = key;
        !folder.isEmpty() && firstBelow(folder).isEmpty();
        folder = parentOf(folder)) {
      missing.push(folder);
    }
    for (String folder : missing) {
      putMarker(folder);
    }
  }

  private void putMarker(String folder) throws IOException {
    try {
      client.putObject(
          request -> request.bucket(bucket).key(folderPrefix(folder)), RequestBody.empty());
    } catch (SdkException e) {
      throw failure("create", folder, e);
    }
  }

  // The object, and the marker of an empty folder by that name. Without its marker, a folder that
  // something is in stays as long as that does.
  @Override
  public void delete(String key) throws IOException {
    try {
      deleteObjects(List.of(objectKey(key), folderPrefix(key)));
    } catch (SdkException e) {
      throw failure("delete", key, e);
    }
  }

  @Override
  public void deleteRecursively(String key) throws IOException {
    Optional<Entry> item = stat(key);
    if (item.isEmpty()) {
      return;
    }
    try {
      if (item.get().isDirectory()) {
        List<String> below = new ArrayList<>();
        for (S3Object object : listAll(folderPrefix(key))) {
          below.add(object.key());
        }
        deleteObjects(below);
      } else {
        deleteObjects(List.of(objectKey(key)));
      }
    } catch (SdkException e) {
      throw failure("delete", key, e);
    }
    // The folder it was in may have been there only because of it.
    String parent = parentOf(key);
    if (!parent.isEmpty() && firstBelow(parent).isEmpty()) {
      putMarker(parent);
    }
  }

  private void deleteObjects(List<String> objectKeys) throws IOException {
    for (int from = 0; from < objectKeys.size(); from += MAX_DELETES_PER_REQUEST) {
      List<ObjectIdentifier> batch =
          objectKeys
              .subList(from, Math.min(from + MAX_DELETES_PER_REQUEST, objectKeys.size()))
              .stream()
              .map(objectKey -> ObjectIdentifier.builder().key(objectKey).build())
              .toList();
      DeleteObjectsResponse response =
          client.deleteObjects(
              request ->
                  request.bucket(bucket).delete(delete -> delete.objects(batch).quiet(true)));
      if (response.hasErrors() && !response.errors().isEmpty()) {
        throw new IOException(
            "S3 did not delete "
                + response.errors().size()
                + " objects, such as "
                + response.errors().getFirst().key()
                + ": "
                + response.errors().getFirst().message());
      }
    }
  }

  private Iterable<S3Object> listAll(String below) {
    ListObjectsV2Request request =
        ListObjectsV2Request.builder().bucket(bucket).prefix(below).build();
    return client.listObjectsV2Paginator(request).contents();
  }

  private String objectKey(String key) {
    String objectKey = prefix + key;
    if (objectKey.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
      throw new BadRequestException("That path is too long to be stored");
    }
    return objectKey;
  }

  private String folderPrefix(String key) {
    return key.isEmpty() ? prefix : objectKey(key) + "/";
  }

  private String keyOf(String objectKey) {
    return objectKey.substring(prefix.length());
  }

  // Keys from the bucket can be anything another tool wrote; only those a path could have are
  // items. Each segment below the folder listed has to be a name.
  static boolean isItemKey(String itemKey, String folder) {
    String rest = folder.isEmpty() ? itemKey : itemKey.substring(folder.length() + 1);
    if (rest.isEmpty()) {
      return false;
    }
    for (String segment : rest.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  private static String parentOf(String key) {
    int slash = key.lastIndexOf('/');
    return slash < 0 ? "" : key.substring(0, slash);
  }

  private static String child(String folder, String name) {
    return folder.isEmpty() ? name : folder + "/" + name;
  }

  private static Entry file(String key, long size, Instant modified) {
    return new Entry(key, false, size, modified, modified);
  }

  private static Entry folder(String key, Instant modified) {
    return new Entry(key, true, 0, modified, modified);
  }

  private static boolean isNotFound(SdkException e) {
    return e instanceof NoSuchKeyException
        || (e instanceof S3Exception s3 && s3.statusCode() == 404);
  }

  private static boolean isTaken(SdkException e) {
    return e instanceof S3Exception s3 && (s3.statusCode() == 412 || s3.statusCode() == 409);
  }

  // What went wrong, as an IOException like the disk's, which the API reports without details.
  private IOException failure(String action, String key, SdkException e) {
    if (isNotFound(e)) {
      return new NoSuchFileException(key);
    }
    if (isTaken(e)) {
      return new FileAlreadyExistsException(key);
    }
    return new IOException(
        "Could not " + action + " " + key + " in " + description() + ": " + reason(e), e);
  }

  private static String reason(SdkException e) {
    if (e instanceof S3Exception s3 && s3.awsErrorDetails() != null) {
      String code = s3.awsErrorDetails().errorCode();
      String message = s3.awsErrorDetails().errorMessage();
      return (code != null ? code + ": " : "") + (message != null ? message : s3.getMessage());
    }
    return e.getMessage();
  }

  /** An object's content, which stops reading from S3 when closed before its end. */
  private static final class ObjectContent extends FilterInputStream {

    private final ResponseInputStream<GetObjectResponse> response;
    private boolean ended;

    ObjectContent(ResponseInputStream<GetObjectResponse> response) {
      super(response);
      this.response = response;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      ended |= b < 0;
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, length);
      ended |= read < 0;
      return read;
    }

    // Closing it otherwise reads the rest, to put the connection back in the pool: the whole file,
    // when a client asked for its first few kilobytes.
    @Override
    public void close() throws IOException {
      if (!ended) {
        response.abort();
      }
      super.close();
    }
  }

  /** {@code length} bytes of a stream that stays open after them, for the next part. */
  private static final class Unclosed extends FilterInputStream {

    private long remaining;

    Unclosed(InputStream in, long length) {
      super(in);
      this.remaining = length;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int b = super.read();
      if (b >= 0) {
        remaining--;
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int read = super.read(buffer, offset, (int) Math.min(length, remaining));
      if (read > 0) {
        remaining -= read;
      }
      return read;
    }

    @Override
    public long skip(long n) throws IOException {
      long skipped = super.skip(Math.min(n, remaining));
      remaining -= skipped;
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return (int) Math.min(super.available(), remaining);
    }

    @Override
    public boolean markSupported() {
      return false;
    }

    @Override
    public void close() {}
  }

  /**
   * A file to send, read from S3 only when the response is written. Skipping to a range before
   * reading asks S3 for just that range, so serving the end of a large file does not read its
   * start.
   */
  private final class ObjectResource extends AbstractResource {

    private final Entry file;

    ObjectResource(Entry file) {
      this.file = file;
    }

    @Override
    public InputStream getInputStream() {
      return new RangedRead(file);
    }

    @Override
    public long contentLength() {
      return file.size();
    }

    @Override
    public long lastModified() {
      return file.modified() != null ? file.modified().toEpochMilli() : 0;
    }

    @Override
    public boolean exists() {
      return true;
    }

    @Override
    public String getFilename() {
      return file.name();
    }

    @Override
    public String getDescription() {
      return "object [s3://" + bucket + "/" + prefix + file.key() + "]";
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ObjectResource that && file.key().equals(that.file.key());
    }

    @Override
    public int hashCode() {
      return file.key().hashCode();
    }
  }

  /** Opened at the first read, from wherever skipping before it got to. */
  private final class RangedRead extends InputStream {

    private final Entry file;
    private long position;
    private InputStream in;

    RangedRead(Entry file) {
      this.file = file;
    }

    @Override
    public long skip(long n) throws IOException {
      if (in != null) {
        return in.skip(n);
      }
      long skipped = Math.max(0, Math.min(n, file.size() - position));
      position += skipped;
      return skipped;
    }

    @Override
    public int read() throws IOException {
      return open().read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      return open().read(buffer, offset, length);
    }

    private InputStream open() throws IOException {
      if (in == null) {
        if (position >= file.size()) {
          in = InputStream.nullInputStream();
          return in;
        }
        String objectKey = objectKey(file.key());
        ResponseInputStream<GetObjectResponse> response;
        try {
          response =
              client.getObject(
                  request -> {
                    request.bucket(bucket).key(objectKey);
                    if (position > 0) {
                      request.range("bytes=" + position + "-");
                    }
                  });
        } catch (SdkException e) {
          throw failure("read", file.key(), e);
        }
        in = new ObjectContent(response);
        // The length was promised to the client already; a file replaced since could break it.
        if (response.response().contentLength() != file.size() - position) {
          in.close();
          throw new IOException(file.key() + " changed while it was being sent");
        }
      }
      return in;
    }

    @Override
    public void close() throws IOException {
      if (in != null) {
        in.close();
      }
    }
  }
}
