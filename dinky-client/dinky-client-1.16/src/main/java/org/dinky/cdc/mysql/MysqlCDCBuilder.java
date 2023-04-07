/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.cdc.mysql;

import org.dinky.assertion.Asserts;
import org.dinky.cdc.AbstractCDCBuilder;
import org.dinky.cdc.CDCBuilder;
import org.dinky.constant.ClientConstant;
import org.dinky.constant.FlinkParamConstant;
import org.dinky.model.FlinkCDCConfig;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.mysql.source.MySqlSourceBuilder;
import com.ververica.cdc.connectors.mysql.table.StartupOptions;

/**
 * MysqlCDCBuilder
 *
 * @author wenmo
 * @since 2022/4/12 21:29
 */
public class MysqlCDCBuilder extends AbstractCDCBuilder {

    public static final String KEY_WORD = "mysql-cdc";
    private static final String METADATA_TYPE = "MySql";

    public MysqlCDCBuilder() {}

    public MysqlCDCBuilder(FlinkCDCConfig config) {
        super(config);
    }

    @Override
    public String getHandle() {
        return KEY_WORD;
    }

    @Override
    public CDCBuilder create(FlinkCDCConfig config) {
        return new MysqlCDCBuilder(config);
    }

    @Override
    public DataStreamSource<String> build(StreamExecutionEnvironment env) {
        String database = config.getDatabase();
        String serverId = config.getSource().get("server-id");
        String serverTimeZone = config.getSource().get("server-time-zone");
        String fetchSize = config.getSource().get("scan.snapshot.fetch.size");
        String connectTimeout = config.getSource().get("connect.timeout");
        String connectMaxRetries = config.getSource().get("connect.max-retries");
        String connectionPoolSize = config.getSource().get("connection.pool.size");
        String heartbeatInterval = config.getSource().get("heartbeat.interval");
        String chunkSize = config.getSource().get("scan.incremental.snapshot.chunk.size");
        String timestampMillis = config.getSource().get("scan.startup.timestampMillis");

        Properties debeziumProperties = new Properties();
        // 为部分转换添加默认值
        debeziumProperties.setProperty("bigint.unsigned.handling.mode", "long");
        debeziumProperties.setProperty("decimal.handling.mode", "string");

        for (Map.Entry<String, String> entry : config.getDebezium().entrySet()) {
            if (Asserts.isNotNullString(entry.getKey())
                    && Asserts.isNotNullString(entry.getValue())) {
                debeziumProperties.setProperty(entry.getKey(), entry.getValue());
            }
        }

        // 添加jdbc参数注入
        Properties jdbcProperties = new Properties();
        for (Map.Entry<String, String> entry : config.getJdbc().entrySet()) {
            if (Asserts.isNotNullString(entry.getKey())
                    && Asserts.isNotNullString(entry.getValue())) {
                jdbcProperties.setProperty(entry.getKey(), entry.getValue());
            }
        }

        MySqlSourceBuilder<String> sourceBuilder =
                MySqlSource.<String>builder()
                        .hostname(config.getHostname())
                        .port(config.getPort())
                        .username(config.getUsername())
                        .password(config.getPassword());

        if (Asserts.isNotNullString(database)) {
            String[] databases = database.split(FlinkParamConstant.SPLIT);
            sourceBuilder.databaseList(databases);
        } else {
            sourceBuilder.databaseList(new String[0]);
        }

        List<String> schemaTableNameList = config.getSchemaTableNameList();
        if (Asserts.isNotNullCollection(schemaTableNameList)) {
            sourceBuilder.tableList(
                    schemaTableNameList.toArray(new String[schemaTableNameList.size()]));
        } else {
            sourceBuilder.tableList(new String[0]);
        }

        sourceBuilder.deserializer(new MysqlJsonDebeziumDeserializationSchema());
        sourceBuilder.debeziumProperties(debeziumProperties);
        sourceBuilder.jdbcProperties(jdbcProperties);

        if (Asserts.isNotNullString(config.getStartupMode())) {
            switch (config.getStartupMode().toLowerCase()) {
                case "initial":
                    sourceBuilder.startupOptions(StartupOptions.initial());
                    break;
                case "latest-offset":
                    sourceBuilder.startupOptions(StartupOptions.latest());
                    break;
                case "earliest-offset":
                    sourceBuilder.startupOptions(StartupOptions.earliest());
                    break;
                case "timestamp":
                    sourceBuilder.startupOptions(
                            StartupOptions.timestamp(
                                    Asserts.isNotNullString(timestampMillis)
                                            ? Long.valueOf(timestampMillis)
                                            : System.currentTimeMillis()));
                    break;
                default:
            }
        } else {
            sourceBuilder.startupOptions(StartupOptions.latest());
        }

        if (Asserts.isNotNullString(serverId)) {
            sourceBuilder.serverId(serverId);
        }

        if (Asserts.isNotNullString(serverTimeZone)) {
            sourceBuilder.serverTimeZone(serverTimeZone);
        }

        if (Asserts.isNotNullString(fetchSize)) {
            sourceBuilder.fetchSize(Integer.valueOf(fetchSize));
        }

        if (Asserts.isNotNullString(connectTimeout)) {
            sourceBuilder.connectTimeout(Duration.ofMillis(Long.valueOf(connectTimeout)));
        }

        if (Asserts.isNotNullString(connectMaxRetries)) {
            sourceBuilder.connectMaxRetries(Integer.valueOf(connectMaxRetries));
        }

        if (Asserts.isNotNullString(connectionPoolSize)) {
            sourceBuilder.connectionPoolSize(Integer.valueOf(connectionPoolSize));
        }

        if (Asserts.isNotNullString(heartbeatInterval)) {
            sourceBuilder.heartbeatInterval(Duration.ofMillis(Long.valueOf(heartbeatInterval)));
        }

        if (Asserts.isAllNotNullString(chunkSize)) {
            sourceBuilder.splitSize(Integer.parseInt(chunkSize));
        }

        return env.fromSource(
                sourceBuilder.build(), WatermarkStrategy.noWatermarks(), "MySQL CDC Source");
    }

    public Map<String, Map<String, String>> parseMetaDataConfigs() {
        Map<String, Map<String, String>> allConfigMap = new HashMap<>();
        List<String> schemaList = getSchemaList();
        for (String schema : schemaList) {
            Map<String, String> configMap = new HashMap<>();
            configMap.put(ClientConstant.METADATA_TYPE, METADATA_TYPE);
            StringBuilder sb = new StringBuilder("jdbc:mysql://");
            sb.append(config.getHostname());
            sb.append(":");
            sb.append(config.getPort());
            sb.append("/");
            sb.append(schema);
            configMap.put(ClientConstant.METADATA_NAME, sb.toString());
            configMap.put(ClientConstant.METADATA_URL, sb.toString());
            configMap.put(ClientConstant.METADATA_USERNAME, config.getUsername());
            configMap.put(ClientConstant.METADATA_PASSWORD, config.getPassword());
            allConfigMap.put(schema, configMap);
        }
        return allConfigMap;
    }

    @Override
    public String getSchemaFieldName() {
        return "db";
    }

    @Override
    public String getSchema() {
        return config.getDatabase();
    }
}