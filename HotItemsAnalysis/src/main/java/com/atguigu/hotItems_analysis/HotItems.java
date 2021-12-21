package com.atguigu.hotItems_analysis;

import com.atguigu.hotItems_analysis.beans.ItemViewCount;
import com.atguigu.hotItems_analysis.beans.UserBehavior;
import org.apache.commons.compress.utils.Lists;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.util.Collector;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Properties;

public class HotItems {

    public static void main(String[] args) throws Exception {
        // 1. 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        // 2. 读取数据,创建DataStream数据流
//        DataStream<String> inputStream = env.readTextFile("D:\\idea_workspace\\UserBehaviorAnalysis\\HotItemsAnalysis\\src\\main\\resources\\UserBehavior.csv");
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers","locahost:9092");
        properties.setProperty("group.id","consumer");
        properties.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.setProperty("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.setProperty("auto.offset.reset", "latest");

        DataStream<String> inputStream = env.addSource(new FlinkKafkaConsumer<String>("hotitems",new SimpleStringSchema(),properties));

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

        // 4. 分组开窗聚合,得到每个窗口内各个商品的count值
        DataStream<ItemViewCount> windowAggStream = dataStream
                .filter(data -> "pv".equals(data.getBehavior())) // 过滤pv行为
                .keyBy("itemId") // 按商品id分组
                .timeWindow(Time.hours(1), Time.minutes(5)) // 开滑动窗口
                .aggregate(new ItemCountAgg(), new WinodwItemCountResult());

        // 5. 收集同一窗口的所有窗口count数据,排序输出top N
        DataStream<String> resultStream = windowAggStream
                .keyBy("windowEnd")  // 按照窗口分组
                .process(new TopNHotItems(3));   // 用自定义处理函数排序,取前5

        resultStream.print();
        env.execute("hot items analysis");
    }

    // 实现自定义增量聚合函数
    public static class ItemCountAgg implements AggregateFunction<UserBehavior, Long, Long> {

        @Override
        public Long createAccumulator() {
            return 0L;
        }

        @Override
        public Long add(UserBehavior value, Long accumulator) {
            return accumulator + 1;
        }

        @Override
        public Long getResult(Long accumulator) {
            return accumulator;
        }

        @Override
        public Long merge(Long a, Long b) {
            return a + b;
        }
    }

    // 自定义全窗口函数
    public static class WinodwItemCountResult implements WindowFunction<Long, ItemViewCount, Tuple, TimeWindow> {
        @Override
        public void apply(Tuple tuple, TimeWindow window, Iterable<Long> input, Collector<ItemViewCount> out) throws Exception {
            Long itemId = tuple.getField(0);
            Long windowEnd = window.getEnd();
            Long count = input.iterator().next();

            out.collect(new ItemViewCount(itemId, windowEnd, count));
        }
    }

    // 实现自定义KeyedProcessFunction
    public static class TopNHotItems extends KeyedProcessFunction<Tuple, ItemViewCount, String> {
        // 定义属性,top n的大小
        private Integer topSize;

        public TopNHotItems(Integer topSize) {
            this.topSize = topSize;
        }

        // 定义列表状态,保存当前窗口内所有输出的ItemViewCount
        ListState<ItemViewCount> itemVienCountListState;

        @Override
        public void open(Configuration parameters) throws Exception {
            itemVienCountListState = getRuntimeContext().getListState(new ListStateDescriptor<ItemViewCount>("item-view-count-list", ItemViewCount.class));
        }

        @Override
        public void processElement(ItemViewCount value, Context ctx, Collector<String> out) throws Exception {

            // 每来一条数据,存入list中,并注册定时器
            itemVienCountListState.add(value);
            ctx.timerService().registerEventTimeTimer(value.getWindowEnd() + 1);
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
            // 定时器触发,当前已收集到所有数据,排序输出
            ArrayList<ItemViewCount> itemViewCounts = Lists.newArrayList(itemVienCountListState.get().iterator());

            itemViewCounts.sort(new Comparator<ItemViewCount>() {
                @Override
                public int compare(ItemViewCount o1, ItemViewCount o2) {
                    return o2.getCount().intValue() - o1.getCount().intValue();
                }
            });

            // 讲排名信息格式化成String,方便打印输出
            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("==============================\n");
            resultBuilder.append("窗口结束时间: ").append(new Timestamp(timestamp - 1)).append("\n");

            // 遍历列表,取top N输出
            for (int i = 0; i < Math.min(topSize, itemViewCounts.size()); i++) {
                ItemViewCount cunrrentItemViewCount = itemViewCounts.get(i);
                resultBuilder.append("No").append(i + 1).append(":")
                        .append("商品ID = ").append(cunrrentItemViewCount.getItemId())
                        .append("热门度 = ").append(cunrrentItemViewCount.getCount())
                        .append("\n");
            }
            resultBuilder.append("==============================\n\n");

            // 控制下输出频率
            Thread.sleep(1000);
            out.collect(resultBuilder.toString());
        }
    }
}
