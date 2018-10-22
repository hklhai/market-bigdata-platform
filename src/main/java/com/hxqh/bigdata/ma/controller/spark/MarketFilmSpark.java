package com.hxqh.bigdata.ma.controller.spark;

import com.hxqh.bigdata.ma.common.Constants;
import com.hxqh.bigdata.ma.conf.ConfigurationManager;
import com.hxqh.bigdata.ma.domain.Film;
import com.hxqh.bigdata.ma.util.DateUtils;
import com.hxqh.bigdata.ma.util.ElasticSearchUtils;
import com.hxqh.bigdata.ma.util.SparkUtil;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import scala.Tuple2;

import java.io.IOException;
import java.util.*;

/**
 * 1. 出品公司排行
 * 2. 演员、导演播放量排行
 * 3. 类别占比排行
 * <p>
 * Created by Ocean lin on 2018/3/15.
 *
 * @author Ocean lin
 */
public class MarketFilmSpark {

    public static void main(String[] args) {
        final SparkSession spark = SparkSession
                .builder()
                .appName("MarketFilmSpark")
                .getOrCreate();
        registerESTable(spark, "film", "film_data", "film");
        String startDate = DateUtils.getYesterdayDate();
        String endDate = DateUtils.getTodayDate();

        String sql = "select * from Film  where  category = 'film'  and addTime >='" + startDate + "' and addTime <= '" + endDate + "'";
        final Dataset<Row> film = spark.sql(sql);


        Properties prop = new Properties();
        prop.setProperty("user", ConfigurationManager.getProperty("spring.datasource.username"));
        prop.setProperty("password", ConfigurationManager.getProperty("spring.datasource.password"));

        // 获取mysql中百度出品公司名称
        Dataset<Row> baiduInfo = spark.read().jdbc(ConfigurationManager.getProperty("spring.datasource.url"),
                "baidu_info", new String[]{"company is not null"}, prop).select("name", "company");

        // 缓存优化
        film.cache();

        final TransportClient client = ElasticSearchUtils.getClient();


        // 播放量Top10
        Integer i = 1;
        List<Tuple2<Long, String>> top10Title = commonTop10(film, Constants.FILM_OFFSET_TITLE, Constants.FILM_OFFSET_PLAYNUM);
        for (Tuple2<Long, String> tuple2 : top10Title) {
            Film f = new Film(new Date(), Double.valueOf(tuple2._1), tuple2._2, Constants.FILM_PLAYNUM, i);
            addFilm(f, client);
            i = i + 1;
        }


        // 分类占比
        i = 1;
        JavaPairRDD<String, Long> stringLongJavaPairRDD = labelPie(film);
        List<Tuple2<String, Long>> collect = stringLongJavaPairRDD.collect();
        for (Tuple2<String, Long> tuple2 : collect) {
            Film f = new Film(new Date(), Double.valueOf(tuple2._2), tuple2._1, Constants.FILM_LABEL_PIE, i);
            addFilm(f, client);
            i = i + 1;
        }


        // 电影评分Top10
        i = 1;
        List<Tuple2<Float, String>> top10TitleByScore =
                commonFloatTop10(film, Constants.FILM_OFFSET_TITLE, Constants.FILM_OFFSET_SCORE);
        for (Tuple2<Float, String> tuple2 : top10TitleByScore) {
            Film f = new Film(new Date(), Double.valueOf(tuple2._1), tuple2._2, Constants.FILM_SCORE_NUM, i);
            addFilm(f, client);
            i = i + 1;
        }

        // 出品公司Top10
        i = 1;
        List<Tuple2<Long, String>> top10RDD = companyPlayNum(film, baiduInfo);
        for (Tuple2<Long, String> tuple2 : top10RDD) {
            addFilm(new Film(new Date(), Double.valueOf(tuple2._1), tuple2._2, Constants.FILM_COMPANY, i), client);
            i = i + 1;
        }


        // 播放量最多演员Top10
        i = 1;
        List<Tuple2<Long, String>> top10Actors = commonTop10(film, Constants.FILM_OFFSET_ACTOR, Constants.FILM_OFFSET_PLAYNUM);
        for (Tuple2<Long, String> tuple2 : top10Actors) {
            addFilm(new Film(new Date(), Double.valueOf(tuple2._1), tuple2._2, Constants.FILM_ACTOR_PLAYNUM, i), client);
            i = i + 1;
        }

        //  评分最高演员Top10
        i = 1;
        List<Tuple2<Float, String>> top10ActorsByScore =
                commonFloatTop10(film, Constants.FILM_OFFSET_ACTOR, Constants.FILM_OFFSET_SCORE);
        for (Tuple2<Float, String> tuple2 : top10ActorsByScore) {
            addFilm(new Film(new Date(), Double.valueOf(tuple2._1), tuple2._2, Constants.FILM_ACTOR_SCORE, i), client);
            i = i + 1;
        }

        // 播放量最多导演Top10
        i = 1;
        List<Tuple2<Long, String>> top10Director = commonTop10(film, Constants.FILM_OFFSET_DIRECTOR, Constants.FILM_OFFSET_PLAYNUM);
        for (Tuple2<Long, String> tuple2 : top10Director) {
            addFilm(new Film(new Date(), Double.valueOf(tuple2._1), tuple2._2, Constants.FILM_DIRECTOR_PLAYNUM, i), client);
            i = i + 1;
        }

        // 评分最高导演Top10
        i = 1;
        List<Tuple2<Float, String>> top10DirectorByScore =
                commonFloatTop10(film, Constants.FILM_OFFSET_DIRECTOR, Constants.FILM_OFFSET_SCORE);
        for (Tuple2<Float, String> tuple2 : top10DirectorByScore) {
            addFilm(new Film(new Date(), Double.valueOf(tuple2._1), tuple2._2, Constants.FILM_DIRECTOR_SCORE, i), client);
            i = i + 1;
        }


    }


    public static ResponseEntity addFilm(Film film, TransportClient client) {
        String indexName = Constants.FILM_INDEX;
        String typeName = Constants.FILM_TYPE;
        try {
            String todayTime = DateUtils.getTodayTime();
            XContentBuilder content = XContentFactory.jsonBuilder().startObject().
                    field("numvalue", film.getNumvalue()).
                    field("name", film.getName()).
                    field("category", film.getCategory()).
                    field("indexNumber", film.getIndexNumber()).
                    field("addTime", todayTime).endObject();

            IndexResponse result = client.prepareIndex(indexName, typeName).setSource(content).get();
            System.out.println(film.getName() + " Persist to ES Success!");
            return new ResponseEntity(result.getId(), HttpStatus.OK);
        } catch (IOException e) {
            e.printStackTrace();
            return new ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    /**
     * @param film      电影RDD
     * @param baiduInfo 百度电影出品公司RDD
     * @return
     */
    // todo 对百度爬取出品公司数据组flatMap操作
    private static List<Tuple2<Long, String>> companyPlayNum(Dataset<Row> film, Dataset<Row> baiduInfo) {
        // [2018-03-14 01:00:21,Film,155,成荫 王炎,南征北战（1974）,华语 战争 普通话,415000,0.0,iqiyi,张勇手 王尚信,58]
        // 电影名称 播放量 聚合累加
        JavaPairRDD<String, Long> filmPairRDD = film.toJavaRDD().mapToPair(new PairFunction<Row, String, Long>() {
            @Override
            public Tuple2<String, Long> call(Row row) throws Exception {
                String filmName = String.valueOf(row.getString(4));
                Long playNum = Long.valueOf(String.valueOf(row.getInt(6)));
                return new Tuple2<>(filmName, playNum);
            }
        }).reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long v1, Long v2) throws Exception {
                return v1 + v2;
            }
        });


        // <电影，公司>名称去重
        JavaPairRDD<String, String> baiduInfoPairRDD = baiduInfo.toJavaRDD().mapToPair(new PairFunction<Row, String, String>() {
            @Override
            public Tuple2<String, String> call(Row row) throws Exception {
                String filmName = String.valueOf(row.getString(0));
                String companyName = String.valueOf(row.getString(1));

                return new Tuple2<>(filmName, companyName);
            }
        }).distinct();

        // join,交换KV
        JavaPairRDD<String, Tuple2<Long, String>> join = filmPairRDD.join(baiduInfoPairRDD);
        JavaPairRDD<Long, String> unSortedRDD = join.mapToPair(new PairFunction<Tuple2<String, Tuple2<Long, String>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<String, Tuple2<Long, String>> tuple2) throws Exception {
                String filmName = tuple2._1;
                Long playNum = tuple2._2._1;
                String companyName = tuple2._2._2;

                return new Tuple2<>(playNum, filmName + "-" + companyName);
            }
        });

        // 降序排序，取出top10
        final List<Tuple2<Long, String>> top10RDD = unSortedRDD.sortByKey(false).take(Constants.FILM_TOP_NUM);
        return top10RDD;
    }


    /**
     * @param film   电影RDD
     * @param offset 演员或导演
     * @return
     */
    private static List<Tuple2<Long, String>> commonTop10(Dataset<Row> film, final Integer offset, final Integer numOffset) {
        // [2018-03-14 01:00:21,Film,155,成荫 王炎,南征北战（1974）,华语 战争 普通话,415000,0.0,iqiyi,张勇手 王尚信,58]
        // 过滤非空，形成<演员，播放量>,<导演，播放量>，flat展开
        JavaPairRDD<String, Long> javaPairRDD = film.toJavaRDD().filter(new Function<Row, Boolean>() {
            @Override
            public Boolean call(Row v1) throws Exception {
                String actors = v1.getString(offset);
                if ("".equals(actors) || actors == null) {
                    return false;
                }
                return true;
            }
        }).mapToPair(new PairFunction<Row, String, Long>() {

            @Override
            public Tuple2<String, Long> call(Row row) throws Exception {
                String actors = row.getString(offset);
                Long playNum = Long.valueOf(String.valueOf(row.getInt(numOffset)));

                return new Tuple2<>(actors, playNum);
            }
        }).flatMapToPair(new PairFlatMapFunction<Tuple2<String, Long>, String, Long>() {

            @Override
            public Iterator<Tuple2<String, Long>> call(Tuple2<String, Long> tuple2) throws Exception {

                List<Tuple2<String, Long>> list = new ArrayList<>();
                String actors = tuple2._1;
                String[] splits;
                if (actors.contains(Constants.FILM_SPLIT_LABEL)) {
                    splits = actors.split(Constants.FILM_SPLIT_LABEL);
                } else {
                    splits = actors.split(Constants.FILM_SPLIT_SPACE);
                }
                Long aLong = tuple2._2;
                for (int i = 0; i < splits.length; i++) {
                    String s = splits[i];
                    list.add(new Tuple2<>(s, aLong));
                }
                return list.iterator();
            }
        });

        // 累加，KV互换，降序排序，取出Top N
        List<Tuple2<Long, String>> top10Actors = javaPairRDD.reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long v1, Long v2) throws Exception {
                return v1 + v2;
            }
        }).mapToPair(new PairFunction<Tuple2<String, Long>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<String, Long> tuple2) throws Exception {
                return new Tuple2<>(tuple2._2, tuple2._1);
            }
        }).sortByKey(false).take(Constants.FILM_TOP_NUM);

        return top10Actors;
    }


    /**
     * @param film   电影RDD
     * @param offset 演员或导演
     * @return
     */
    private static List<Tuple2<Float, String>> commonFloatTop10(Dataset<Row> film, final Integer offset, final Integer numOffset) {
        // [2018-03-14 01:00:21,Film,155,成荫 王炎,南征北战（1974）,华语 战争 普通话,415000,0.0,iqiyi,张勇手 王尚信,58]
        // 过滤非空，形成<演员，播放量>,<导演，播放量>，flat展开
        JavaPairRDD<String, Float> javaPairRDD = film.toJavaRDD().filter(new Function<Row, Boolean>() {
            @Override
            public Boolean call(Row v1) throws Exception {
                String actors = v1.getString(offset);
                if ("".equals(actors) || actors == null) {
                    return false;
                }
                return true;
            }
        }).mapToPair(new PairFunction<Row, String, Float>() {

            @Override
            public Tuple2<String, Float> call(Row row) throws Exception {
                String actors = row.getString(offset);
                Float score = Float.valueOf(String.valueOf(row.getFloat(numOffset)));
                return new Tuple2<>(actors, score);
            }
        }).flatMapToPair(new PairFlatMapFunction<Tuple2<String, Float>, String, Float>() {

            @Override
            public Iterator<Tuple2<String, Float>> call(Tuple2<String, Float> tuple2) throws Exception {

                List<Tuple2<String, Float>> list = new ArrayList<>();
                String actors = tuple2._1;
                String[] splits;
                if (actors.contains(Constants.FILM_SPLIT_LABEL)) {
                    splits = actors.split(Constants.FILM_SPLIT_LABEL);
                } else {
                    splits = actors.split(Constants.FILM_SPLIT_SPACE);
                }
                Float aLong = tuple2._2;
                for (int i = 0; i < splits.length; i++) {
                    String s = splits[i];
                    list.add(new Tuple2<>(s, aLong));
                }
                return list.iterator();
            }
        });

        // 累加，KV互换，降序排序，取出Top N
        List<Tuple2<Float, String>> top10Actors = javaPairRDD.distinct().mapToPair(new PairFunction<Tuple2<String, Float>, Float, String>() {
            @Override
            public Tuple2<Float, String> call(Tuple2<String, Float> tuple2) throws Exception {
                return new Tuple2<>(tuple2._2, tuple2._1);
            }
        }).sortByKey(false).take(Constants.FILM_TOP_NUM);

        return top10Actors;
    }

    /**
     * 计算分类饼图数据
     *
     * @param film 电影RDD
     * @return
     */
    private static JavaPairRDD<String, Long> labelPie(Dataset<Row> film) {
        // [2018-03-14 01:00:21,Film,155,成荫 王炎,南征北战（1974）,华语 战争 普通话,415000,0.0,iqiyi,张勇手 王尚信,58]
        JavaPairRDD<String, Long> pairRDD = film.toJavaRDD().flatMapToPair(new PairFlatMapFunction<Row, String, Long>() {
            @Override
            public Iterator<Tuple2<String, Long>> call(Row row) throws Exception {
                List<Tuple2<String, Long>> list = new ArrayList<>();
                String label = String.valueOf(row.getString(5));

                String[] splits;
                if (label.contains(Constants.FILM_SPLIT_LABEL)) {
                    splits = label.split(Constants.FILM_SPLIT_LABEL);
                } else {
                    splits = label.split(Constants.FILM_SPLIT_SPACE);
                }
                for (int i = 0; i < splits.length; i++) {
                    list.add(new Tuple2<>(splits[i], 1L));
                }
                return list.iterator();
            }
        }).reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long v1, Long v2) throws Exception {
                return v1 + v2;
            }
        }).mapToPair(new PairFunction<Tuple2<String, Long>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<String, Long> tuple2) throws Exception {
                return new Tuple2<>(tuple2._2, tuple2._1);
            }
        }).sortByKey(false).filter(new Function<Tuple2<Long, String>, Boolean>() {
            @Override
            public Boolean call(Tuple2<Long, String> v1) throws Exception {
                Long aLong = v1._1;
                if (aLong > 10L) {
                    return true;
                } else {
                    return false;
                }
            }
        }).mapToPair(new PairFunction<Tuple2<Long, String>, String, Long>() {
            @Override
            public Tuple2<String, Long> call(Tuple2<Long, String> tuple2) throws Exception {
                return new Tuple2<>(tuple2._2, tuple2._1);
            }
        });

        return pairRDD;
    }


    /**
     * 获取ElasticSearch中的索引注册为表
     *
     * @param spark     SparkSession
     * @param tableName 临时表名称
     * @param indexName index名称
     * @param typeName  type名称
     */
    private static void registerESTable(SparkSession spark, String tableName, String indexName, String typeName) {
        Map<String, String> esOptions = SparkUtil.initOption();
        Dataset<Row> dataset = spark.read().format("org.elasticsearch.spark.sql")
                .options(esOptions)
                .load(indexName + "/" + typeName);
        dataset.registerTempTable(tableName);
    }


}
