/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.binlog.reader;

import com.alibaba.otter.canal.filter.aviater.AviaterRegexFilter;
import com.alibaba.otter.canal.parse.inbound.mysql.MysqlEventParser;
import com.alibaba.otter.canal.parse.support.AuthenticationInfo;
import com.alibaba.otter.canal.protocol.position.EntryPosition;
import com.dtstack.flinkx.binlog.BinlogJournalValidator;
import com.dtstack.flinkx.inputformat.BaseRichInputFormat;
import com.dtstack.flinkx.restore.FormatState;
import com.dtstack.flinkx.util.ExceptionUtil;
import com.dtstack.flinkx.util.RetryUtil;
import com.google.common.base.Joiner;
import com.mysql.jdbc.exceptions.jdbc4.MySQLSyntaxErrorException;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.core.io.GenericInputSplit;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

/**
 * @author toutian
 */
public class BinlogInputFormat extends BaseRichInputFormat {

    private static final Logger LOG = LoggerFactory.getLogger(BinlogInputFormat.class);

    private BinlogConfig binlogConfig;

    private volatile EntryPosition entryPosition;

    private List<String> categories = new ArrayList<>();

    private final String SCHEMA_SPLIT = ".";

    private final String AUTHORITY_REPLICATION_TEMPLATE = "show master status";

    private final String AUTHORITY_TEMPLATE = "SELECT count(1) FROM %s LIMIT 1";

    private final String QUERY_SCHEMA_TABLE_TEMPLATE = "SELECT TABLE_NAME From information_schema.TABLES WHERE TABLE_SCHEMA='%s' LIMIT 1";

    private static final int RETRY_TIMES = 3;

    private static final int SLEEP_TIME = 2000;
    /**
     * internal fields
     */
    private transient MysqlEventParser controller;

    private transient BinlogEventSink binlogEventSink;

    public void updateLastPos(EntryPosition entryPosition) {
        this.entryPosition = entryPosition;
    }

    @Override
    public void openInputFormat() throws IOException {
        super.openInputFormat();

        LOG.info("binlog configure...");

        if (StringUtils.isNotEmpty(binlogConfig.getCat())) {
            LOG.info("{}", categories);
            categories = Arrays.asList(binlogConfig.getCat().toUpperCase().split(","));
        }
        /**
         * mysql 数据解析关注的表，Perl正则表达式.

         多个正则之间以逗号(,)分隔，转义符需要双斜杠(\\)


         常见例子：

         1.  所有表：.*   or  .*\\..*
         2.  canal schema下所有表： canal\\..*
         3.  canal下的以canal打头的表：canal\\.canal.*
         4.  canal schema下的一张表：canal\\.test1

         5.  多个规则组合使用：canal\\..*,mysql.test1,mysql.test2 (逗号分隔)
         */
        List<String> tables = binlogConfig.getTable();
        String jdbcUrl = binlogConfig.getJdbcUrl();
        if (jdbcUrl != null) {
            int idx = jdbcUrl.lastIndexOf('?');
            String database;
            if (idx != -1) {
                database = StringUtils.substring(jdbcUrl, jdbcUrl.lastIndexOf('/') + 1, idx);
            } else {
                database = StringUtils.substring(jdbcUrl, jdbcUrl.lastIndexOf('/') + 1);
            }

            if (CollectionUtils.isNotEmpty(tables)) {
                HashMap<String, String> checkedTable = new HashMap<>(tables.size());
                //按照.切割字符串需要转义
                String regexSchemaSplit = "\\" + SCHEMA_SPLIT;
                String filter = tables.stream()
                        //每一个表格式化为schema.tableName格式
                        .map(t -> formatTableName(database, t))
                        //只需要每个schema下的一个表进行判断
                        .peek(t -> checkedTable.putIfAbsent(t.split(regexSchemaSplit)[0], t))
                        .collect(Collectors.joining(","));

                binlogConfig.setFilter(filter);

                //检验每个schema下的第一个表的权限
                checkSourceAuthority(null, checkedTable.values());
            } else {
                //如果table未指定 只消费此schema下的数据
                binlogConfig.setFilter(database + "\\..*");
                //检验schema下任意一张表的权限
                checkSourceAuthority(database, null);
            }
        }
    }

    @Override
    public FormatState getFormatState() {
        if (!restoreConfig.isRestore()) {
            LOG.info("return null for formatState");
            return null;
        }

        super.getFormatState();
        if (formatState != null) {
            formatState.setState(entryPosition);
        }
        return formatState;
    }

    public boolean accept(String type) {
        return categories.isEmpty() || categories.contains(type);
    }

    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        if (inputSplit.getSplitNumber() != 0) {
            LOG.info("binlog openInternal split number:{} abort...", inputSplit.getSplitNumber());
            return;
        }

        LOG.info("binlog openInternal split number:{} start...", inputSplit.getSplitNumber());
        LOG.info("binlog config:{}", binlogConfig.toString());

        controller = new MysqlEventParser();
        controller.setConnectionCharset(Charset.forName(binlogConfig.getConnectionCharset()));
        controller.setSlaveId(binlogConfig.getSlaveId());
        controller.setDetectingEnable(binlogConfig.getDetectingEnable());
        controller.setDetectingSQL(binlogConfig.getDetectingSql());
        controller.setMasterInfo(new AuthenticationInfo(new InetSocketAddress(binlogConfig.getHost(), binlogConfig.getPort()), binlogConfig.getUsername(), binlogConfig.getPassword()));
        controller.setEnableTsdb(binlogConfig.getEnableTsdb());
        controller.setDestination("example");
        controller.setParallel(binlogConfig.getParallel());
        controller.setParallelBufferSize(binlogConfig.getBufferSize());
        controller.setParallelThreadSize(binlogConfig.getParallelThreadSize());
        controller.setIsGTIDMode(binlogConfig.getGtidMode());

        controller.setAlarmHandler(new BinlogAlarmHandler(this));

        BinlogEventSink sink = new BinlogEventSink(this);
        sink.setPavingData(binlogConfig.getPavingData());
        binlogEventSink = sink;

        controller.setEventSink(sink);

        controller.setLogPositionManager(new BinlogPositionManager(this));

        EntryPosition startPosition = findStartPosition();
        if (startPosition != null) {
            controller.setMasterPosition(startPosition);
        }

        if (StringUtils.isNotEmpty(binlogConfig.getFilter())) {
            controller.setEventFilter(new AviaterRegexFilter(binlogConfig.getFilter()));
        }

        controller.start();
    }

    @Override
    protected Row nextRecordInternal(Row row) throws IOException {
        if (binlogEventSink != null) {
            return binlogEventSink.takeEvent();
        }
        LOG.warn("binlog park start");
        LockSupport.park(this);
        LOG.warn("binlog park end...");
        return Row.of();
    }

    @Override
    protected void closeInternal() throws IOException {
        if (controller != null && controller.isStart()) {
            controller.stop();
            controller = null;
            LOG.info("binlog closeInternal..., entryPosition:{}", formatState != null ? formatState.getState() : null);
        }

    }

    private EntryPosition findStartPosition() {
        EntryPosition startPosition = null;
        if (formatState != null && formatState.getState() != null && formatState.getState() instanceof EntryPosition) {
            startPosition = (EntryPosition) formatState.getState();
            checkBinlogFile(startPosition.getJournalName());
        } else if (MapUtils.isNotEmpty(binlogConfig.getStart())) {
            startPosition = new EntryPosition();
            String journalName = (String) binlogConfig.getStart().get("journalName");
            checkBinlogFile(journalName);

            if (StringUtils.isNotEmpty(journalName)) {
                startPosition.setJournalName(journalName);
            }

            startPosition.setTimestamp(MapUtils.getLong(binlogConfig.getStart(), "timestamp"));
            startPosition.setPosition(MapUtils.getLong(binlogConfig.getStart(), "position"));
        }

        return startPosition;
    }

    private void checkBinlogFile(String journalName) {
        if (StringUtils.isNotEmpty(journalName)) {
            if (!new BinlogJournalValidator(binlogConfig.getHost(), binlogConfig.getPort(), binlogConfig.getUsername(), binlogConfig.getPassword()).check(journalName)) {
                throw new IllegalArgumentException("Can't find journalName: " + journalName);
            }
        }
    }

    @Override
    public InputSplit[] createInputSplitsInternal(int minNumSplits) throws Exception {
        InputSplit[] splits = new InputSplit[minNumSplits];
        for (int i = 0; i < minNumSplits; i++) {
            splits[i] = new GenericInputSplit(i, minNumSplits);
        }
        return splits;
    }

    @Override
    public boolean reachedEnd() throws IOException {
        return false;
    }

    public BinlogConfig getBinlogConfig() {
        return binlogConfig;
    }

    public void setBinlogConfig(BinlogConfig binlogConfig) {
        this.binlogConfig = binlogConfig;
    }

    private String formatTableName(String schemaName, String tableName) {
        StringBuilder stringBuilder = new StringBuilder();
        if (tableName.contains(SCHEMA_SPLIT)) {
            return tableName;
        } else {
            return stringBuilder.append(schemaName).append(SCHEMA_SPLIT).append(tableName).toString();
        }
    }

    /**
     * @param schema 需要校验权限的schemaName
     * @param tables 需要校验权限的tableName
     *               schemaName权限验证 取schemaName下第一个表进行验证判断整个schemaName下是否具有权限
     */
    private void checkSourceAuthority(String schema, Collection<String> tables) {

        try (Connection connection = RetryUtil.executeWithRetry(() -> DriverManager.getConnection(binlogConfig.getJdbcUrl(), binlogConfig.getUsername(), binlogConfig.getPassword()), RETRY_TIMES, SLEEP_TIME, false)) {
            try (Statement statement = connection.createStatement()) {

                //判断用户是否具有REPLICATION权限 没有的话会直接抛出异常MySQLSyntaxErrorException
                statement.execute((AUTHORITY_REPLICATION_TEMPLATE));
                //Schema不为空 就获取一张表判断权限
                if (StringUtils.isNotBlank(schema)) {
                    try (ResultSet resultSet = statement.executeQuery(String.format(QUERY_SCHEMA_TABLE_TEMPLATE, schema))) {
                        if (resultSet.next()) {
                            String tableName = resultSet.getString(1);
                            if (CollectionUtils.isNotEmpty(tables)) {
                                tables.add(formatTableName(schema, tableName));
                            } else {
                                tables = Collections.singletonList(formatTableName(schema, tableName));
                            }
                        }
                    }
                }
                if (CollectionUtils.isEmpty(tables)) {
                    return;
                }

                List<String> failedTables = new ArrayList<>(tables.size());
                RuntimeException runtimeException = null;
                for (String tableName : tables) {
                    try {
                        //判断用户是否具备tableName下的读权限
                        statement.executeQuery(String.format(AUTHORITY_TEMPLATE, tableName));
                    } catch (SQLException e) {
                        failedTables.add(tableName);
                        if (Objects.isNull(runtimeException)) {
                            runtimeException = new RuntimeException(e);
                        }
                    }
                }

                if (CollectionUtils.isNotEmpty(failedTables)) {
                    String message = String.format("user【%s】is not granted table 【%s】select permission %s",
                            binlogConfig.getUsername(),
                            Joiner.on(",").join(failedTables),
                            ExceptionUtil.getErrorMessage(runtimeException));

                    LOG.error("{}", message);
                    throw runtimeException;
                }
            }
        } catch (Exception e) {
            String message;
            if (e instanceof MySQLSyntaxErrorException) {
                message = String.format(" jdbcUrl【%s】 make sure that the database configuration is  correct and user 【%s】 has right permissions example REPLICATION SLAVE,REPLICATION CLIENT..  %s",
                        binlogConfig.getJdbcUrl(),
                        binlogConfig.getUsername(),
                        ExceptionUtil.getErrorMessage(e));
            } else {
                message = String.format("checkSourceAuthority error, url 【%s】 userName【%s】",
                        binlogConfig.getJdbcUrl(),
                        binlogConfig.getUsername());
            }
            LOG.error("{}", message);
            throw new RuntimeException(message, e);

        }
    }

}
