package com.atguigu.hotItems_analysis;

import com.atguigu.hotItems_analysis.beans.UserBehavior;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Slide;
import org.apache.flink.table.api.Table;

import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.types.Row;

public class HotItemsWithSql {
    public static void main(String[] args) throws Exception {
        // 1. 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        // 2. 读取数据,创建DataStream数据流
        DataStream<String> inputStream = env.readTextFile("D:\\idea_workspace\\UserBehaviorAnalysis\\HotItemsAnalysis\\src\\main\\resources\\UserBehavior.csv");

        // 3. 转换成POJO,分配时间和watermark
        DataStream<UserBehavior> dataStream = inputStream
                .map(line -> {
                    String[] fields = line.split(",");
                    return new UserBehavior(new Long(fields[0]), new Long(fields[1]), new Integer(fields[2]), fields[3], new Long(fields[4]));
                })
                .assignTimestampsAndWatermarks(new AscendingTimestampExtractor<UserBehavior>() {
                    @Override
                    public long extractAscendingTimestamp(UserBehavior element) {
                        return element.getTimestamp() * 1000L;
                    }
                });

        // 4. 创建表执行环境,用Blink版本
        EnvironmentSettings settings = EnvironmentSettings.newInstance()
                .useBlinkPlanner()
                .inStreamingMode()
                .build();

        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, settings);

        // 5. 将流转换成表
        Table dataTable = tableEnv.fromDataStream(dataStream, "itemId,behavior,timestamp.rowtime as ts"); // 可以直接提取自己想要的字段 重命名

        // 6. 分组开窗
        // table api
        Table windowAggTable = dataTable
                .filter("behavior = 'pv'")
                .window(Slide.over("1.hours").every("5.minutes").on("ts").as("w"))
                .groupBy("itemId,w")
                .select("itemId,w.end as windowEnd,itemId.count as cnt");

        // 7. 利用开窗函数,对count值进行排序,并获取rownum,得到topN
        // SQL
        DataStream<Row> aggStream = tableEnv.toAppendStream(windowAggTable, Row.class);
        tableEnv.createTemporaryView("agg", aggStream, "itemId,windowEnd,cnt");

        Table resultTable = tableEnv.sqlQuery("select * from " +
                "(select *,ROW_NUMBER() over (partition by windowEnd order by cnt desc) as row_num " +
                "from agg)" +
                " where row_num <= 5");

        // 纯SQL实现
        tableEnv.createTemporaryView("data_table",dataStream,"itemId,behavior,timestamp.rowtime as ts");

        Table resulteSqlTable = tableEnv.sqlQuery("select * from " +
                "(select *,ROW_NUMBER() over (partition by windowEnd order by cnt desc) as row_num " +
                "from ( " +
                " select itemId,count(itemId) as cnt, HOP_END(ts,interval '5' minute , interval '1' hour) as windowEnd" +
                " from data_table " +
                "where behavior = 'pv' " +
                "group by itemId,HOP(ts,interval '5' minute , interval '1' hour)" +
                " ) " +
                ")" +
                " where row_num <= 5");

//        tableEnv.toRetractStream(resultTable, Row.class).print();
        tableEnv.toRetractStream(resulteSqlTable, Row.class).print();

        env.execute("hot items with sql job");

    }
}

