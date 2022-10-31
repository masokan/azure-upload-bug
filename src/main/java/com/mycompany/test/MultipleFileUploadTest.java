package com.mycompany.test;

import com.azure.core.util.Context;
import com.azure.core.util.FluxUtil;
import com.azure.storage.common.ParallelTransferOptions;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeDirectoryClient;
import com.azure.storage.file.datalake.DataLakeFileAsyncClient;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakePathClient;
import com.azure.storage.file.datalake.DataLakePathClientBuilder;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import com.azure.storage.file.datalake.models.PathInfo;

import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class MultipleFileUploadTest {
  private DataLakeServiceClientBuilder serviceClientBuilder;
  private DataLakePathClientBuilder pathClientBuilder;
  private String localDirName;
  private String remoteDirName;

  private class OneFileUploader implements Runnable {
    private File localFile;
    private String remoteName;
    private Exception exception;

    public OneFileUploader(File localFile, String remoteName) {
      this.localFile = localFile;
      this.remoteName = remoteName;
      exception = null;
    }

    public Exception getException() {
      return exception;
    }

    @Override
    public void run() {
      try {
        DataLakeFileAsyncClient fileClient = getFileAsyncClient(remoteName);
        MonoSubscriber subscriber = new MonoSubscriber<Void>();
        Mono<Void> mono = fileClient.uploadFromFile(localFile.getAbsolutePath(),
                                                    true);
        mono.subscribe(subscriber);
        boolean success = subscriber.waitForCompletion();
        if (!success) {
          exception = new IOException("Upload timed out");
        } else {
          Throwable t = subscriber.getThrowable();
          if (t != null) { // Some exception occurred during data transfer
            exception = new Exception(t);
          }
        }
        System.err.println("Finished uploading " + localFile + " to "
                           + remoteName);
        System.err.println("Deleting local file " + localFile);
        // Delete the local file
        localFile.delete();
      } catch(Exception e) {
        exception = e;
      }
    }

    @Override
    public String toString() {
      return("Local file:" + localFile + " Remote file:" + remoteName);
    }
  }

  public MultipleFileUploadTest(String accountName, String accessToken,
      String localDirName, String remoteDirName) {
    String endpoint = String.format(Locale.ROOT,
                          "https://%s.dfs.core.windows.net", accountName);
    StorageSharedKeyCredential credential
        = new StorageSharedKeyCredential(accountName, accessToken);
    HashSet<String> queryParams = new HashSet<>();
    queryParams.add("recursive");
    queryParams.add("resource");
    queryParams.add("action");
    queryParams.add("position");
    queryParams.add("retainUncommittedData");
    queryParams.add("close");
    serviceClientBuilder = new DataLakeServiceClientBuilder()
                               .endpoint(endpoint).credential(credential)
                               .httpLogOptions(new HttpLogOptions()
                               .setLogLevel(HttpLogDetailLevel.HEADERS)
                               .setAllowedQueryParamNames(queryParams));
    pathClientBuilder = new DataLakePathClientBuilder()
                            .endpoint(endpoint).credential(credential)
                            .httpLogOptions(new HttpLogOptions()
                            .setLogLevel(HttpLogDetailLevel.HEADERS)
                            .setAllowedQueryParamNames(queryParams));
    this.localDirName = localDirName;
    this.remoteDirName = remoteDirName;
  }

  public void runTest(int numFiles, int numDirs, int numThreads)
      throws Exception {
    ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
    OneFileUploader[] uploaders = new OneFileUploader[numFiles];
    int i;
    String[] pathComponents = splitPath(remoteDirName);
    DataLakeFileSystemClient fileSystemClient
         = serviceClientBuilder.buildClient()
                               .getFileSystemClient(pathComponents[0]);
    System.err.println("Creating remote directories");
    for (i = 0; i < numDirs; i++) {
      String subdirName = remoteDirName + "/dir_" + i;
      System.err.println("Creating remote directory " + subdirName);
      // Create the remote subdirectories
      fileSystemClient.createDirectoryIfNotExists(pathComponents[1]);
    }
    System.err.println("Creating local files");
    for (i = 0; i < numFiles; i++) {
      File localFile = new File(localDirName, "f_" + i);
      // Create the local file
      createLocalFile(localFile, 10*1024L);
      String remotePath = remoteDirName + "/dir_" + i % numDirs + "/f_" + i;
      uploaders[i] = new OneFileUploader(localFile, remotePath);
    }
    System.err.println("Creating upload requests");
    // Run uploads in multiple threads
    for (i = 0; i < numFiles; i++) {
      threadPool.submit(uploaders[i]);
    }
    threadPool.shutdown();
    System.err.println("Waiting for upload requests to finish");
    try {
      while (!threadPool.awaitTermination(600, TimeUnit.SECONDS)) {
      }
    } catch(InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    // Check for IOExceptions
    for(OneFileUploader oneFileUploader : uploaders) {
      Exception ioException = oneFileUploader.getException();
      if (ioException != null) {
        System.err.println("Exception occurred for: " + oneFileUploader);
        throw ioException;
      }
    }
  }

  public boolean deleteRemoteDir(String remotePath) throws IOException {
    DataLakeDirectoryClient directoryClient = getDirectoryClient(remotePath);
    try {
      directoryClient.deleteWithResponse(true, null, null, Context.NONE);
    } catch(Exception e) {
      throw new IOException(e);
    }
    return true;
  }

  private static void createLocalFile(File localFile, long fileSize)
      throws IOException {
    int BUF_SIZE = 10*1024;
    Random random = new Random();
    byte[] record = new byte[BUF_SIZE];
    try (FileOutputStream output = new FileOutputStream(localFile)) {
      for(; fileSize > 0; fileSize -= BUF_SIZE) {
        random.nextBytes(record);
        output.write(record);
      }
    }
  }

  private String[] splitPath(String remotePath) throws IOException {
    if (remotePath.startsWith("/")) {
      // Remove any leading "/"
      remotePath = remotePath.substring(1);
    }
    int delimLoc = remotePath.indexOf('/');
    String bucket;
    String object;
    if (delimLoc != -1) {
      bucket = remotePath.substring(0, delimLoc);
      object = remotePath.substring(delimLoc + 1);
    } else {
      throw new IOException("Invalid remote path");
    }
    String[] pathComponents = {bucket, object};
    return pathComponents;
  }

  private DataLakeFileAsyncClient getFileAsyncClient(String remotePath)
      throws IOException {
    String[] pathComponents = splitPath(remotePath);
    DataLakeFileAsyncClient fileAsyncClient = null;
    try {
      fileAsyncClient = pathClientBuilder
                        .fileSystemName(pathComponents[0])
                        .pathName(pathComponents[1])
                        .buildFileAsyncClient();
    } catch(Exception e) {
      throw new IOException(e);
    }
    return fileAsyncClient;
  }

  private DataLakeDirectoryClient getDirectoryClient(String remotePath)
      throws IOException {
    String[] pathComponents = splitPath(remotePath);
    DataLakeDirectoryClient directoryClient = null;
    try {
      directoryClient = pathClientBuilder
                        .fileSystemName(pathComponents[0])
                        .pathName(pathComponents[1])
                        .buildDirectoryClient();
    } catch(Exception e) {
      throw new IOException(e);
    }
    return directoryClient;
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 5) {
      MultipleFileUploadTest test
          = new MultipleFileUploadTest(args[0], args[1], args[3], args[4]);
      try {
        int numFiles = Integer.parseInt(args[2]);
        test.runTest(numFiles, 10, 10);
        System.out.println("Test successful");
      } finally {
        test.deleteRemoteDir(args[4]);
      }
    } else {
      System.err.println("Usage: java -jar <jarFile> <accountName>"
          + " <accessToken> <numFiles> <localDirName> <remoteDirName>");
      System.exit(1);
    }
  }

}
