/*
 * Copyright 2022-2023 Google LLC
 * Copyright 2013-2021 CompilerWorks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.edwmigration.dumper.application.dumper.connector.snowflake;

import com.google.auto.service.AutoService;
import com.google.common.base.CaseFormat;
import com.google.edwmigration.dumper.application.dumper.ConnectorArguments;
import com.google.edwmigration.dumper.application.dumper.connector.Connector;
import com.google.edwmigration.dumper.application.dumper.connector.ConnectorProperty;
import com.google.edwmigration.dumper.application.dumper.connector.MetadataConnector;
import com.google.edwmigration.dumper.application.dumper.connector.ResultSetTransformer;
import com.google.edwmigration.dumper.application.dumper.task.AbstractJdbcTask;
import com.google.edwmigration.dumper.application.dumper.task.DumpMetadataTask;
import com.google.edwmigration.dumper.application.dumper.task.FormatTask;
import com.google.edwmigration.dumper.application.dumper.task.JdbcSelectTask;
import com.google.edwmigration.dumper.application.dumper.task.Summary;
import com.google.edwmigration.dumper.application.dumper.task.Task;
import com.google.edwmigration.dumper.plugin.ext.jdk.annotation.Description;
import com.google.edwmigration.dumper.plugin.lib.dumper.spi.SnowflakeMetadataDumpFormat;
import com.google.edwmigration.dumper.plugin.lib.dumper.spi.SnowflakeMetadataDumpFormat.DatabasesFormat.Header;
import com.google.errorprone.annotations.ForOverride;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connector to Snowflake databases.
 *
 * @author matt
 */
@AutoService({Connector.class, MetadataConnector.class})
@Description("Dumps metadata from Snowflake.")
public class SnowflakeMetadataConnector extends AbstractSnowflakeConnector
    implements MetadataConnector, SnowflakeMetadataDumpFormat {

  @SuppressWarnings("UnusedVariable")
  private static final Logger LOG = LoggerFactory.getLogger(SnowflakeMetadataConnector.class);

  public enum SnowflakeMetadataConnectorProperties implements ConnectorProperty {
    DATABASES_OVERRIDE_QUERY,
    DATABASES_OVERRIDE_WHERE,
    SCHEMATA_OVERRIDE_QUERY,
    SCHEMATA_OVERRIDE_WHERE,
    TABLES_OVERRIDE_QUERY,
    TABLES_OVERRIDE_WHERE,
    COLUMNS_OVERRIDE_QUERY,
    COLUMNS_OVERRIDE_WHERE,
    VIEWS_OVERRIDE_QUERY,
    VIEWS_OVERRIDE_WHERE,
    FUNCTIONS_OVERRIDE_QUERY,
    FUNCTIONS_OVERRIDE_WHERE,
    TABLE_STORAGE_METRICS_OVERRIDE_QUERY,
    TABLE_STORAGE_METRICS_OVERRIDE_WHERE;

    private final String name;
    private final String description;

    SnowflakeMetadataConnectorProperties() {
      boolean isWhere = name().endsWith("WHERE");
      String name = name().split("_")[0].toLowerCase();
      this.name = "snowflake.metadata." + name + (isWhere ? ".where" : ".query");
      this.description =
          isWhere
              ? "Custom where condition to append to query for metadata " + name + " dump."
              : "Custom query for metadata " + name + " dump.";
    }

    @Nonnull
    public String getName() {
      return name;
    }

    @Nonnull
    public String getDescription() {
      return description;
    }
  }

  protected SnowflakeMetadataConnector(@Nonnull String name) {
    super(name);
  }

  public SnowflakeMetadataConnector() {
    this("snowflake");
  }

  @Nonnull
  @Override
  public Class<? extends Enum<? extends ConnectorProperty>> getConnectorProperties() {
    return SnowflakeMetadataConnectorProperties.class;
  }

  protected static class TaskVariant {

    public final String zipEntryName;
    public final String schemaName;
    public final String whereClause;

    public TaskVariant(String zipEntryName, String schemaName, String whereClause) {
      this.zipEntryName = zipEntryName;
      this.schemaName = schemaName;
      this.whereClause = whereClause;
    }

    public TaskVariant(String zipEntryName, String schemaName) {
      this(zipEntryName, schemaName, "");
    }
  }

  /** Adds the ACCOUNT_USAGE task, with a fallback to the INFORMATION_SCHEMA task. */
  @ForOverride
  protected void addSqlTasksWithInfoSchemaFallback(
      @Nonnull List<? super Task<?>> out,
      @Nonnull Class<? extends Enum<?>> header,
      @Nonnull String format,
      @Nonnull TaskVariant is_task,
      @Nonnull TaskVariant au_task,
      ConnectorArguments arguments) {
    AbstractJdbcTask<Summary> is_jdbcTask =
        new JdbcSelectTask(
                is_task.zipEntryName,
                String.format(format, is_task.schemaName, is_task.whereClause))
            .withHeaderClass(header);

    AbstractJdbcTask<Summary> au_jdbcTask =
        new JdbcSelectTask(
                au_task.zipEntryName,
                String.format(format, au_task.schemaName, au_task.whereClause))
            .withHeaderClass(header);

    if (arguments.isAssessment()) {
      out.add(au_jdbcTask);
    } else {
      out.add(au_jdbcTask);
      out.add(is_jdbcTask.onlyIfFailed(au_jdbcTask));
    }
  }

  private void addSingleSqlTask(
      @Nonnull List<? super Task<?>> out,
      @Nonnull String format,
      @Nonnull TaskVariant task,
      @Nonnull ResultSetTransformer<String[]> transformer) {
    out.add(
        new JdbcSelectTask(
                task.zipEntryName, String.format(format, task.schemaName, task.whereClause))
            .withHeaderTransformer(transformer));
  }

  private String[] transformHeaderToCamelCase(ResultSet rs, CaseFormat baseFormat)
      throws SQLException {
    ResultSetMetaData metaData = rs.getMetaData();
    int columnCount = metaData.getColumnCount();
    String[] columns = new String[columnCount];
    for (int i = 0; i < columnCount; i++) {
      columns[i] = baseFormat.to(CaseFormat.UPPER_CAMEL, metaData.getColumnLabel(i + 1));
    }
    return columns;
  }

  @Override
  public void addTasksTo(
      @Nonnull List<? super Task<?>> out, @Nonnull ConnectorArguments arguments) {
    out.add(new DumpMetadataTask(arguments, FORMAT_NAME));
    out.add(new FormatTask(FORMAT_NAME));

    boolean INJECT_IS_FAULT = arguments.isTestFlag('A');
    final String IS = INJECT_IS_FAULT ? "__NONEXISTENT__" : "INFORMATION_SCHEMA";
    final String AU = "SNOWFLAKE.ACCOUNT_USAGE";
    final String AU_WHERE = " WHERE DELETED IS NULL";

    // Docref: https://docs.snowflake.net/manuals/sql-reference/info-schema.html#list-of-views
    // ACCOUNT_USAGE is much faster than INFORMATION_SCHEMA and does not have the size limitations,
    // but requires extra privileges to be granted.
    // https://docs.snowflake.net/manuals/sql-reference/account-usage.html
    // https://docs.snowflake.net/manuals/user-guide/data-share-consumers.html
    // You must: GRANT IMPORTED PRIVILEGES ON DATABASE snowflake TO ROLE <SOMETHING>;
    addSqlTasksWithInfoSchemaFallback(
        out,
        Header.class,
        getOverrideableQuery(
            arguments,
            "SELECT database_name, database_owner FROM %1$s.DATABASES%2$s",
            SnowflakeMetadataConnectorProperties.DATABASES_OVERRIDE_QUERY,
            SnowflakeMetadataConnectorProperties.DATABASES_OVERRIDE_WHERE),
        new TaskVariant(DatabasesFormat.IS_ZIP_ENTRY_NAME, IS),
        new TaskVariant(DatabasesFormat.AU_ZIP_ENTRY_NAME, AU, AU_WHERE),
        arguments);

    addSqlTasksWithInfoSchemaFallback(
        out,
        SchemataFormat.Header.class,
        getOverrideableQuery(
            arguments,
            "SELECT catalog_name, schema_name FROM %1$s.SCHEMATA%2$s",
            SnowflakeMetadataConnectorProperties.SCHEMATA_OVERRIDE_QUERY,
            SnowflakeMetadataConnectorProperties.SCHEMATA_OVERRIDE_WHERE),
        new TaskVariant(SchemataFormat.IS_ZIP_ENTRY_NAME, IS),
        new TaskVariant(SchemataFormat.AU_ZIP_ENTRY_NAME, AU, AU_WHERE),
        arguments);

    addSqlTasksWithInfoSchemaFallback(
        out,
        TablesFormat.Header.class,
        getOverrideableQuery(
            arguments,
            "SELECT table_catalog, table_schema, table_name, table_type, row_count, bytes,"
                + " clustering_key FROM %1$s.TABLES%2$s",
            SnowflakeMetadataConnectorProperties.TABLES_OVERRIDE_QUERY,
            SnowflakeMetadataConnectorProperties.TABLES_OVERRIDE_WHERE),
        new TaskVariant(TablesFormat.IS_ZIP_ENTRY_NAME, IS),
        new TaskVariant(TablesFormat.AU_ZIP_ENTRY_NAME, AU, AU_WHERE),
        arguments); // Painfully slow.

    addSqlTasksWithInfoSchemaFallback(
        out,
        ColumnsFormat.Header.class,
        getOverrideableQuery(
            arguments,
            "SELECT table_catalog, table_schema, table_name, ordinal_position, column_name,"
                + " data_type FROM %1$s.COLUMNS%2$s",
            SnowflakeMetadataConnectorProperties.COLUMNS_OVERRIDE_QUERY,
            SnowflakeMetadataConnectorProperties.COLUMNS_OVERRIDE_WHERE),
        new TaskVariant(ColumnsFormat.IS_ZIP_ENTRY_NAME, IS),
        new TaskVariant(ColumnsFormat.AU_ZIP_ENTRY_NAME, AU, AU_WHERE),
        arguments); // Very fast.

    addSqlTasksWithInfoSchemaFallback(
        out,
        ViewsFormat.Header.class,
        getOverrideableQuery(
            arguments,
            "SELECT table_catalog, table_schema, table_name, view_definition FROM %1$s.VIEWS%2$s",
            SnowflakeMetadataConnectorProperties.VIEWS_OVERRIDE_QUERY,
            SnowflakeMetadataConnectorProperties.VIEWS_OVERRIDE_WHERE),
        new TaskVariant(ViewsFormat.IS_ZIP_ENTRY_NAME, IS),
        new TaskVariant(ViewsFormat.AU_ZIP_ENTRY_NAME, AU, AU_WHERE),
        arguments);

    addSqlTasksWithInfoSchemaFallback(
        out,
        FunctionsFormat.Header.class,
        getOverrideableQuery(
            arguments,
            "SELECT function_schema, function_name, data_type, argument_signature FROM"
                + " %1$s.FUNCTIONS%2$s",
            SnowflakeMetadataConnectorProperties.FUNCTIONS_OVERRIDE_QUERY,
            SnowflakeMetadataConnectorProperties.FUNCTIONS_OVERRIDE_WHERE),
        new TaskVariant(FunctionsFormat.IS_ZIP_ENTRY_NAME, IS),
        new TaskVariant(FunctionsFormat.AU_ZIP_ENTRY_NAME, AU, AU_WHERE),
        arguments);

    if (arguments.isAssessment()) {
      addSingleSqlTask(
          out,
          getOverrideableQuery(
              arguments,
              "SELECT * FROM %1$s.TABLE_STORAGE_METRICS%2$s",
              SnowflakeMetadataConnectorProperties.TABLE_STORAGE_METRICS_OVERRIDE_QUERY,
              SnowflakeMetadataConnectorProperties.TABLE_STORAGE_METRICS_OVERRIDE_WHERE),
          new TaskVariant(TableStorageMetricsFormat.AU_ZIP_ENTRY_NAME, AU),
          rs -> transformHeaderToCamelCase(rs, CaseFormat.UPPER_UNDERSCORE));

      ResultSetTransformer<String[]> lowerUnderscoreTransformer =
          rs -> transformHeaderToCamelCase(rs, CaseFormat.LOWER_UNDERSCORE);
      addSingleSqlTask(
          out,
          "SHOW WAREHOUSES",
          new TaskVariant(WarehousesFormat.AU_ZIP_ENTRY_NAME, AU),
          lowerUnderscoreTransformer);
      addSingleSqlTask(
          out,
          "SHOW EXTERNAL TABLES",
          new TaskVariant(ExternalTablesFormat.AU_ZIP_ENTRY_NAME, AU),
          lowerUnderscoreTransformer);
      addSingleSqlTask(
          out,
          "SHOW FUNCTIONS",
          new TaskVariant(FunctionInfoFormat.AU_ZIP_ENTRY_NAME, AU),
          lowerUnderscoreTransformer);
    }
  }

  private String getOverrideableQuery(
      @Nonnull ConnectorArguments arguments,
      @Nonnull String defaultSql,
      @Nonnull SnowflakeMetadataConnectorProperties queryProperty,
      @Nonnull SnowflakeMetadataConnectorProperties whereProperty) {

    String overrideQuery = arguments.getDefinition(queryProperty);
    if (overrideQuery != null) {
      return overrideQuery;
    }

    String overrideWhere = arguments.getDefinition(whereProperty);
    if (overrideWhere != null) {
      return defaultSql + " WHERE " + overrideWhere;
    }

    return defaultSql;
  }
}
