/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.hadoop;

import com.netflix.iceberg.TableMetadata;
import com.netflix.iceberg.TableMetadataParser;
import com.netflix.iceberg.TableOperations;
import com.netflix.iceberg.exceptions.CommitFailedException;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.exceptions.ValidationException;
import com.netflix.iceberg.io.InputFile;
import com.netflix.iceberg.io.OutputFile;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.UUID;

/**
 * TableOperations implementation for file systems that support atomic rename.
 * <p>
 * This maintains metadata in a "metadata" folder under the table location.
 */
class HadoopTableOperations implements TableOperations {
  private static final Logger LOG = LoggerFactory.getLogger(HadoopTableOperations.class);

  private final Configuration conf;
  private final Path location;
  private TableMetadata currentMetadata = null;
  private Integer version = null;

  HadoopTableOperations(Path location, Configuration conf) {
    this.conf = conf;
    this.location = location;
    refresh();
  }

  public TableMetadata current() {
    return currentMetadata;
  }

  @Override
  public TableMetadata refresh() {
    int ver = version != null ? version : readVersionHint();
    Path metadataFile = metadataFile(ver);
    FileSystem fs = Util.getFS(metadataFile, conf);
    try {
      // don't check if the file exists if version is non-null because it was already checked
      if (version == null && !fs.exists(metadataFile)) {
        if (ver == 0) {
          // no v0 metadata means the table doesn't exist yet
          return null;
        }
        throw new ValidationException("Metadata file is missing: %s", metadataFile);
      }

      while (fs.exists(metadataFile(ver + 1))) {
        ver += 1;
        metadataFile = metadataFile(ver);
      }

    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to get file system for path: %s", metadataFile);
    }
    this.version = ver;
    this.currentMetadata = TableMetadataParser.read(this,
        HadoopInputFile.fromPath(metadataFile, conf));
    return currentMetadata;
  }

  @Override
  public void commit(TableMetadata base, TableMetadata metadata) {
    if (base != currentMetadata) {
      throw new CommitFailedException("Cannot commit changes based on stale table metadata");
    }

    if (base == metadata) {
      LOG.info("Nothing to commit.");
      return;
    }

    Path tempMetadataFile = metadataPath(UUID.randomUUID().toString() + ".metadata.json");
    TableMetadataParser.write(metadata, HadoopOutputFile.fromPath(tempMetadataFile, conf));

    int nextVersion = (version != null ? version : 0) + 1;
    Path finalMetadataFile = metadataFile(nextVersion);
    FileSystem fs = Util.getFS(tempMetadataFile, conf);

    try {
      if (fs.exists(finalMetadataFile)) {
        throw new CommitFailedException(
            "Version %d already exists: %s", nextVersion, finalMetadataFile);
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e,
          "Failed to check if next version exists: " + finalMetadataFile);
    }

    try {
      // this rename operation is the atomic commit operation
      if (!fs.rename(tempMetadataFile, finalMetadataFile)) {
        throw new CommitFailedException(
            "Failed to commit changes using rename: %s", finalMetadataFile);
      }
    } catch (IOException e) {
      throw new CommitFailedException(e,
          "Failed to commit changes using rename: %s", finalMetadataFile);
    }

    // update the best-effort version pointer
    writeVersionHint(nextVersion);

    refresh();
  }

  @Override
  public InputFile newInputFile(String path) {
    return HadoopInputFile.fromPath(new Path(path), conf);
  }

  @Override
  public OutputFile newMetadataFile(String filename) {
    return HadoopOutputFile.fromPath(metadataPath(filename), conf);
  }

  @Override
  public void deleteFile(String path) {
    Path toDelete = new Path(path);
    FileSystem fs = Util.getFS(toDelete, conf);
    try {
      fs.delete(toDelete, false /* not recursive */ );
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to delete file: %s", path);
    }
  }

  @Override
  public long newSnapshotId() {
    return System.currentTimeMillis();
  }

  private Path metadataFile(int version) {
    return metadataPath("v" + version + ".metadata.json");
  }

  private Path metadataPath(String filename) {
    return new Path(new Path(location, "metadata"), filename);
  }

  private Path versionHintFile() {
    return metadataPath("version-hint.text");
  }

  private void writeVersionHint(int version) {
    Path versionHintFile = versionHintFile();
    FileSystem fs = Util.getFS(versionHintFile, conf);

    try (FSDataOutputStream out = fs.create(versionHintFile, true /* overwrite */ )) {
      out.write(String.valueOf(version).getBytes("UTF-8"));

    } catch (IOException e) {
      LOG.warn("Failed to update version hint", e);
    }
  }

  private int readVersionHint() {
    Path versionHintFile = versionHintFile();
    try {
      FileSystem fs = versionHintFile.getFileSystem(conf);
      if (!fs.exists(versionHintFile)) {
        return 0;
      }

      try (BufferedReader in = new BufferedReader(new InputStreamReader(fs.open(versionHintFile)))) {
        return Integer.parseInt(in.readLine().replace("\n", ""));
      }

    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to get file system for path: %s", versionHintFile);
    }
  }
}
