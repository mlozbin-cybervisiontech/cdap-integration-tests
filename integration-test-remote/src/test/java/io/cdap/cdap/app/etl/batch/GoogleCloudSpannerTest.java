/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.cdap.cdap.app.etl.batch;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ByteArray;
import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.Instance;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Value;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.cdap.cdap.api.artifact.ArtifactScope;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.app.etl.DataprocETLTestBase;
import io.cdap.cdap.common.ArtifactNotFoundException;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.BatchSource;
import io.cdap.cdap.etl.proto.ArtifactSelectorConfig;
import io.cdap.cdap.etl.proto.v2.ETLBatchConfig;
import io.cdap.cdap.etl.proto.v2.ETLPlugin;
import io.cdap.cdap.etl.proto.v2.ETLStage;
import io.cdap.cdap.proto.ProgramRunStatus;
import io.cdap.cdap.proto.artifact.AppRequest;
import io.cdap.cdap.proto.id.ApplicationId;
import io.cdap.cdap.proto.id.ArtifactId;
import io.cdap.cdap.test.ApplicationManager;
import io.cdap.cdap.test.Tasks;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Tests reading to and writing from Google Cloud Spanner within a Dataproc cluster.
 */
public class GoogleCloudSpannerTest extends DataprocETLTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(GoogleCloudSpannerTest.class);

  private static final String SPANNER_PLUGIN_NAME = "Spanner";
  private static final String SPANNER_SOURCE_STAGE_NAME = "SpannerSourceStage";
  private static final String SPANNER_SINK_STAGE_NAME = "SpannerSinkStage";
  private static final String SOURCE_TABLE_NAME = "source";
  private static final String SINK_TABLE_NAME = "sink";

  // todo CDAP-14233 - add support for array
  private static final String TABLE_FORMAT = "CREATE TABLE %s (" +
    "ID INT64," +
    "STRING_COL STRING(MAX)," +
    "BOOL_COL BOOL," +
    "BYTES_COL BYTES(MAX)," +
    "DATE_COL DATE," +
    "FLOAT_COL FLOAT64," +
    "TIMESTAMP_COL TIMESTAMP," +
    "NOT_IN_THE_SCHEMA_COL STRING(MAX)," +
    ") PRIMARY KEY (ID)";

  private static final Schema SCHEMA = Schema.recordOf(
    "schema",
    Schema.Field.of("ID", Schema.nullableOf(Schema.of(Schema.Type.LONG))),
    Schema.Field.of("STRING_COL", Schema.nullableOf(Schema.of(Schema.Type.STRING))),
    Schema.Field.of("BOOL_COL", Schema.nullableOf(Schema.of(Schema.Type.BOOLEAN))),
    Schema.Field.of("BYTES_COL", Schema.nullableOf(Schema.of(Schema.Type.BYTES))),
    Schema.Field.of("DATE_COL", Schema.nullableOf(Schema.of(Schema.LogicalType.DATE))),
    Schema.Field.of("FLOAT_COL", Schema.nullableOf(Schema.of(Schema.Type.DOUBLE))),
    Schema.Field.of("TIMESTAMP_COL", Schema.nullableOf(Schema.of(Schema.LogicalType.TIMESTAMP_MICROS)))
  );

  private static final ZonedDateTime NOW = ZonedDateTime.now();
  private static final Function<String, List<Mutation>> TEST_MUTATIONS = (tableName) -> ImmutableList.of(
    Mutation.newInsertBuilder(tableName)
      .set("ID").to(1)
      .build(),

    Mutation.newInsertBuilder(tableName)
      .set("ID").to(2)
      .set("STRING_COL").to("some string")
      .set("BOOL_COL").to(false)
      .set("BYTES_COL").to(ByteArray.copyFrom("some value".getBytes()))
      .set("DATE_COL").to(Date.fromYearMonthDay(NOW.getYear(), NOW.getMonthValue(), NOW.getDayOfMonth()))
      .set("FLOAT_COL").to(Double.MIN_VALUE) // FLOAT64 can be used to store Double.MIN_VALUE
      .set("TIMESTAMP_COL").to(Timestamp.ofTimeSecondsAndNanos(NOW.toEpochSecond(), NOW.getNano()))
      .set("NOT_IN_THE_SCHEMA_COL").to("some string")
      .build()
  );

  private static final List<Mutation> SOURCE_TABLE_TEST_MUTATIONS = TEST_MUTATIONS.apply(SOURCE_TABLE_NAME);

  private static Spanner spanner;
  private static Instance instance;
  private static Database database;

  @BeforeClass
  public static void testClassSetup() throws IOException {
    try (InputStream inputStream = new ByteArrayInputStream(
      getServiceAccountCredentials().getBytes(StandardCharsets.UTF_8))) {
      spanner = SpannerOptions.newBuilder()
        .setProjectId(getProjectId())
        .setCredentials(GoogleCredentials.fromStream(inputStream))
        .build()
        .getService();
    }

    instance = createInstance();
    database = createDatabase(instance);
  }

  @AfterClass
  public static void testClassClear() {
    if (spanner != null) {
      LOG.info("Deleting instance {}", instance.getId().getInstance());
      spanner.getInstanceAdminClient().deleteInstance(instance.getId().getInstance());
    }
  }

  @Override
  protected void innerSetup() throws Exception {
    Tasks.waitFor(true, () -> {
      try {
        final ArtifactId dataPipelineId = TEST_NAMESPACE.artifact("cdap-data-pipeline", version);
        return isSpannerPluginExists(dataPipelineId, BatchSource.PLUGIN_TYPE) &&
          isSpannerPluginExists(dataPipelineId, BatchSink.PLUGIN_TYPE);
      } catch (ArtifactNotFoundException e) {
        return false;
      }
    }, 5, TimeUnit.MINUTES, 3, TimeUnit.SECONDS);
  }


  @Override
  protected void innerTearDown() {
  }

  @Before
  public void testSetup() throws Exception {
    super.testSetup();
    // Truncate source table
    spanner.getDatabaseClient(database.getId())
      .write(Collections.singletonList(Mutation.delete(SOURCE_TABLE_NAME, KeySet.all())));

    // Insert test data
    spanner.getDatabaseClient(database.getId()).write(SOURCE_TABLE_TEST_MUTATIONS);
  }

  @Test
  public void testReadAndStoreInNewTable() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_source")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", SOURCE_TABLE_NAME)
      .put("schema", SCHEMA.toString())
      .build();

    String nonExistentSinkTableName = "nonexistent_" + UUID.randomUUID().toString().replaceAll("-", "_");
    Map<String, String> sinkProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_sink")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", nonExistentSinkTableName)
      .put("schema", SCHEMA.toString())
      .put("keys", "ID")
      .build();

    String applicationName = SPANNER_PLUGIN_NAME + "-testReadAndStoreInNewTable";
    ApplicationManager applicationManager = deployApplication(sourceProperties, sinkProperties, applicationName);
    startWorkFlow(applicationManager, ProgramRunStatus.COMPLETED);

    checkMetrics(applicationName, SOURCE_TABLE_TEST_MUTATIONS.size());
    verifySinkData(nonExistentSinkTableName);
  }

  @Test
  public void testReadAndStore() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_source")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", SOURCE_TABLE_NAME)
      .put("schema", SCHEMA.toString())
      .build();

    Map<String, String> sinkProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_sink")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", SINK_TABLE_NAME)
      .put("schema", SCHEMA.toString())
      .build();

    String applicationName = SPANNER_PLUGIN_NAME + "-testReadAndStore";
    ApplicationManager applicationManager = deployApplication(sourceProperties, sinkProperties, applicationName);
    startWorkFlow(applicationManager, ProgramRunStatus.COMPLETED);

    checkMetrics(applicationName, SOURCE_TABLE_TEST_MUTATIONS.size());

    ResultSet resultSet = spanner.getDatabaseClient(database.getId())
      .singleUse()
      .executeQuery(Statement.of(String.format("select * from %s;", SINK_TABLE_NAME)));
    verifySinkData(resultSet);
    Assert.assertTrue(resultSet.isNull("NOT_IN_THE_SCHEMA_COL"));
  }

  @Test
  public void testReadAndStoreInNewTableWithNoSourceSchema() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_source")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", SOURCE_TABLE_NAME)
      .build();

    String nonExistentSinkTableName = "nonexistent_" + UUID.randomUUID().toString().replaceAll("-", "_");
    Map<String, String> sinkProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_sink")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", nonExistentSinkTableName)
      .put("schema", SCHEMA.toString())
      .put("keys", "ID")
      .build();

    String applicationName = SPANNER_PLUGIN_NAME + "-testReadAndStoreInNewTableWithNoSourceSchema";
    ApplicationManager applicationManager = deployApplication(sourceProperties, sinkProperties, applicationName);
    startWorkFlow(applicationManager, ProgramRunStatus.COMPLETED);

    checkMetrics(applicationName, SOURCE_TABLE_TEST_MUTATIONS.size());
    verifySinkData(nonExistentSinkTableName);
  }

  @Test
  public void testReadAndStoreWithNoSourceSchema() throws Exception {
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_source")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", SOURCE_TABLE_NAME)
      .build();

    Map<String, String> sinkProperties = new ImmutableMap.Builder<String, String>()
      .put("referenceName", "spanner_sink")
      .put("instance", instance.getId().getInstance())
      .put("database", database.getId().getDatabase())
      .put("table", SINK_TABLE_NAME)
      .put("schema", SCHEMA.toString())
      .build();

    String applicationName = SPANNER_PLUGIN_NAME + "-testReadAndStoreWithNoSourceSchema";
    ApplicationManager applicationManager = deployApplication(sourceProperties, sinkProperties, applicationName);
    startWorkFlow(applicationManager, ProgramRunStatus.COMPLETED);

    checkMetrics(applicationName, SOURCE_TABLE_TEST_MUTATIONS.size());

    ResultSet resultSet = spanner.getDatabaseClient(database.getId())
      .singleUse()
      .executeQuery(Statement.of(String.format("select * from %s;", SINK_TABLE_NAME)));
    verifySinkData(resultSet);
    Assert.assertTrue(resultSet.isNull("NOT_IN_THE_SCHEMA_COL"));
  }

  private void checkMetrics(String applicationName, int expectedCount) throws Exception {
    Map<String, String> tags = ImmutableMap.of(Constants.Metrics.Tag.NAMESPACE, TEST_NAMESPACE.getNamespace(),
                                               Constants.Metrics.Tag.APP, applicationName);
    checkMetric(tags, "user." + SPANNER_SOURCE_STAGE_NAME + ".records.out", expectedCount, 10);
    checkMetric(tags, "user." + SPANNER_SINK_STAGE_NAME + ".records.in", expectedCount, 10);
  }

  private void verifySinkData(String tableName) {
    ResultSet resultSet = spanner.getDatabaseClient(database.getId())
      .singleUse()
      .executeQuery(Statement.of(String.format("select * from %s;", tableName)));
    verifySinkData(resultSet);
  }

  private void verifySinkData(ResultSet resultSet) {
    Assert.assertTrue(resultSet.next());
    Map<String, Value> firstRowExpected = SOURCE_TABLE_TEST_MUTATIONS.get(0).asMap();
    Assert.assertEquals(firstRowExpected.get("ID").getInt64(), resultSet.getLong("ID"));

    Assert.assertTrue(resultSet.next());
    Map<String, Value> secondRowExpected = SOURCE_TABLE_TEST_MUTATIONS.get(1).asMap();
    Assert.assertEquals(secondRowExpected.get("ID").getInt64(), resultSet.getLong("ID"));
    Assert.assertEquals(secondRowExpected.get("STRING_COL").getString(), resultSet.getString("STRING_COL"));
    Assert.assertEquals(secondRowExpected.get("BOOL_COL").getBool(), resultSet.getBoolean("BOOL_COL"));
    Assert.assertEquals(secondRowExpected.get("BYTES_COL").getBytes(), resultSet.getBytes("BYTES_COL"));
    Assert.assertEquals(secondRowExpected.get("DATE_COL").getDate(), resultSet.getDate("DATE_COL"));
    Assert.assertEquals(secondRowExpected.get("FLOAT_COL").getFloat64(), resultSet.getDouble("FLOAT_COL"), 0.00001);
    Assert.assertEquals(secondRowExpected.get("TIMESTAMP_COL").getTimestamp(), resultSet.getTimestamp("TIMESTAMP_COL"));
  }

  private boolean isSpannerPluginExists(ArtifactId dataPipelineId, String pluginType) throws Exception {
    return artifactClient.getPluginSummaries(dataPipelineId, pluginType, ArtifactScope.SYSTEM).stream()
      .anyMatch(pluginSummary -> SPANNER_PLUGIN_NAME.equals(pluginSummary.getName()));
  }

  private static Instance createInstance() {
    String instanceId = "spanner-test-instance-" + UUID.randomUUID().toString();
    InstanceInfo instanceInfo = InstanceInfo.newBuilder(InstanceId.of(getProjectId(), instanceId))
      .setDisplayName("spanner-test-instance")
      .setNodeCount(1)
      .setInstanceConfigId(InstanceConfigId.of(getProjectId(), "regional-us-central1"))
      .build();

    LOG.info("Creating instance {}", instanceId);
    Instance instance = spanner.getInstanceAdminClient()
      .createInstance(instanceInfo)
      .waitFor()
      .getResult();
    LOG.info("Created instance {}", instanceId);

    return instance;
  }

  private static Database createDatabase(Instance instance) {
    String databaseName = "spanner_test_database";
    LOG.info("Creating instance {}", databaseName);
    Database database = spanner.getDatabaseAdminClient()
      .createDatabase(instance.getId().getInstance(), databaseName, ImmutableList.of(
        String.format(TABLE_FORMAT, SOURCE_TABLE_NAME),
        String.format(TABLE_FORMAT, SINK_TABLE_NAME)))
      .waitFor()
      .getResult();
    LOG.info("Created instance {}", databaseName);

    return database;
  }

  private ApplicationManager deployApplication(Map<String, String> sourceProperties,
                                               Map<String, String> sinkProperties,
                                               String applicationName) throws Exception {

    ArtifactSelectorConfig artifact = new ArtifactSelectorConfig("SYSTEM", "google-cloud", "[0.0.0, 100.0.0)");
    ETLPlugin sourcePlugin = new ETLPlugin(SPANNER_PLUGIN_NAME, BatchSource.PLUGIN_TYPE, sourceProperties, artifact);
    ETLPlugin sinkPlugin = new ETLPlugin(SPANNER_PLUGIN_NAME, BatchSink.PLUGIN_TYPE, sinkProperties, artifact);

    ETLBatchConfig etlConfig = ETLBatchConfig.builder()
      .addStage(new ETLStage(SPANNER_SOURCE_STAGE_NAME, sourcePlugin))
      .addStage(new ETLStage(SPANNER_SINK_STAGE_NAME, sinkPlugin))
      .addConnection(SPANNER_SOURCE_STAGE_NAME, SPANNER_SINK_STAGE_NAME)
      .build();

    AppRequest<ETLBatchConfig> appRequest = getBatchAppRequestV2(etlConfig);
    ApplicationId appId = TEST_NAMESPACE.app(applicationName);
    return deployApplication(appId, appRequest);
  }
}
