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

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.netflix.iceberg.DataFile;
import com.netflix.iceberg.DataFiles;
import com.netflix.iceberg.PartitionSpec;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.Table;
import com.netflix.iceberg.TableMetadata;
import com.netflix.iceberg.TableMetadataParser;
import com.netflix.iceberg.types.Types;
import org.apache.hadoop.conf.Configuration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static com.netflix.iceberg.Files.localInput;
import static com.netflix.iceberg.types.Types.NestedField.optional;
import static com.netflix.iceberg.types.Types.NestedField.required;

public class HadoopTableTestBase {
  // Schema passed to create tables
  static final Schema SCHEMA = new Schema(
      required(3, "id", Types.IntegerType.get()),
      required(4, "data", Types.StringType.get())
  );

  // This is the actual schema for the table, with column IDs reassigned
  static final Schema TABLE_SCHEMA = new Schema(
      required(1, "id", Types.IntegerType.get()),
      required(2, "data", Types.StringType.get())
  );

  static final Schema UPDATED_SCHEMA = new Schema(
      required(1, "id", Types.IntegerType.get()),
      required(2, "data", Types.StringType.get()),
      optional(3, "n", Types.IntegerType.get())
  );

  // Partition spec used to create tables
  static final PartitionSpec SPEC = PartitionSpec.builderFor(SCHEMA)
      .bucket("data", 16)
      .build();

  static final HadoopTables TABLES = new HadoopTables(new Configuration());

  static final DataFile FILE_A = DataFiles.builder(SPEC)
      .withPath("/path/to/data-a.parquet")
      .withFileSizeInBytes(0)
      .withPartitionPath("data_bucket=0") // easy way to set partition data for now
      .withRecordCount(2) // needs at least one record or else metrics will filter it out
      .build();
  static final DataFile FILE_B = DataFiles.builder(SPEC)
      .withPath("/path/to/data-b.parquet")
      .withFileSizeInBytes(0)
      .withPartitionPath("data_bucket=1") // easy way to set partition data for now
      .withRecordCount(2) // needs at least one record or else metrics will filter it out
      .build();
  static final DataFile FILE_C = DataFiles.builder(SPEC)
      .withPath("/path/to/data-a.parquet")
      .withFileSizeInBytes(0)
      .withPartitionPath("data_bucket=2") // easy way to set partition data for now
      .withRecordCount(2) // needs at least one record or else metrics will filter it out
      .build();
  static final DataFile FILE_D = DataFiles.builder(SPEC)
      .withPath("/path/to/data-a.parquet")
      .withFileSizeInBytes(0)
      .withPartitionPath("data_bucket=3") // easy way to set partition data for now
      .withRecordCount(2) // needs at least one record or else metrics will filter it out
      .build();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  File tableDir = null;
  String tableLocation = null;
  File metadataDir = null;
  File versionHintFile = null;
  Table table = null;

  @Before
  public void setupTable() throws Exception {
    this.tableDir = temp.newFolder();
    tableDir.delete(); // created by table create

    this.tableLocation = tableDir.toURI().toString();
    this.metadataDir = new File(tableDir, "metadata");
    this.versionHintFile = new File(metadataDir, "version-hint.text");
    this.table = TABLES.create(SCHEMA, SPEC, tableLocation);
  }

  List<File> listMetadataFiles(String ext) {
    return Lists.newArrayList(metadataDir.listFiles(
        (dir, name) -> Files.getFileExtension(name).equalsIgnoreCase(ext)));
  }

  File version(int i) {
    return new File(metadataDir, "v" + i + ".metadata.json");
  }

  TableMetadata readMetadataVersion(int version) {
    return TableMetadataParser.read(null, localInput(version(version)));
  }

  int readVersionHint() throws IOException {
    return Integer.parseInt(Files.readFirstLine(versionHintFile, Charsets.UTF_8));
  }

  void replaceVersionHint(int version) throws IOException {
    // remove the checksum that will no longer match
    new File(metadataDir, ".version-hint.text.crc").delete();
    Files.write(String.valueOf(version), versionHintFile, Charsets.UTF_8);
  }
}
